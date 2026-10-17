import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.win32.W32APIOptions;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.JSlider;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.AWTEvent;
import java.awt.AWTException;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * ScreenDimmer — a lightweight, always-dark screen dimming overlay for Windows.
 *
 * How it works: instead of touching Windows' brightness APIs (which do not work
 * on most desktop/external monitors and have a hardware floor), it shows a
 * pure-black translucent window on top of everything. The window is made
 * click-through and non-focusable via native window styles (JNA), so it never
 * intercepts input, never steals focus and has no taskbar presence.
 *
 * Controls: left-click the tray icon for the dark popup (sun-to-moon slider,
 * enable/disable); right-click for the tray menu (toggle, quit). Clicking away
 * minimizes the popup back to the tray.
 * Settings persist in ~/.screendimmer.properties.
 */
public class ScreenDimmer {

    // ---------------------------------------------------------------- tuning
    static final String VERSION = "1.0.0";
    static final int MAX_DIM = 90;                       // never offer a fully black screen
    // fallback palette, used only if FlatLaf fails to load; FlatLaf supplies the real theme colors
    static final Color BG        = hex("#1d2127");
    static final Color BORDER    = hex("#3a4048");
    static final Color ACCENT    = hex("#7aa2f7");
    static final Color ACCENT_TX = hex("#11141a");
    static final Color ICON_OFF  = hex("#6b7280");
    static final Color SUN       = hex("#e8c268");

    static Color hex(String s) { return Color.decode(s); }

    // ------------------------------------------------------------- FlatLaf
    static boolean themeInit;

    static void initTheme() {
        if (themeInit) return;
        themeInit = true;
        try {
            FlatLaf.setGlobalExtraDefaults(Collections.singletonMap("@accentColor", "#7aa2f7"));
            UIManager.setLookAndFeel(new FlatDarkLaf());
        } catch (Throwable t) {
            System.err.println("[ScreenDimmer] FlatLaf unavailable, using fallback palette: " + t);
        }
    }

    static Color panelColor() {
        Color c = UIManager.getColor("Panel.background");
        return c != null ? c : BG;
    }

    static Color borderColor() {
        Color c = UIManager.getColor("Component.borderColor");
        return c != null ? c : BORDER;
    }

    // ----------------------------------------------------------------- state
    static int dimPercent = 30;
    static boolean enabled = true;
    static boolean nativeReady;
    static final File cfgFile = new File(System.getProperty("user.home"), ".screendimmer.properties");
    static final List<Frame> overlays = new ArrayList<>();
    static TrayIcon tray;
    static javax.swing.Timer saveTimer;

    public static void main(String[] args) {
        load();
        initNative();

        boolean translucent;
        try {
            translucent = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                    .isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.TRANSLUCENT);
        } catch (Throwable t) {
            translucent = false;
        }
        if (!nativeReady) System.err.println("[ScreenDimmer] native styles unavailable - overlays disabled (would block the mouse).");
        if (!translucent) System.err.println("[ScreenDimmer] window translucency unsupported - overlays disabled.");
        final boolean canDim = nativeReady && translucent;

        SwingUtilities.invokeLater(() -> {
            initTheme();
            createTray();
            if (canDim) applyDim();
            // keep overlays in sync when monitors are added/resolved/rearranged
            javax.swing.Timer watch = new javax.swing.Timer(4000, e -> { if (dimActive() && overlaysStale()) applyDim(); });
            watch.setRepeats(true);
            watch.start();
        });
    }

    static boolean dimActive() { return enabled && dimPercent > 0 && nativeReady; }

    // -------------------------------------------------------- native (JNA)
    static final String OS = System.getProperty("os.name", "").toLowerCase();
    static final boolean isWindows = OS.contains("win");
    static final boolean isLinux = OS.contains("linux") || OS.contains("nix") || OS.contains("nux");

    interface User32 extends Library {
        User32 INSTANCE = Native.load("user32", User32.class, W32APIOptions.UNICODE_OPTIONS);
        Pointer FindWindow(String lpClassName, String lpWindowName);
        int GetWindowLong(Pointer hWnd, int nIndex);
        int SetWindowLong(Pointer hWnd, int nIndex, int dwNewLong);
    }

    interface X11 extends Library {
        X11 INSTANCE = Native.load("X11", X11.class);
        Pointer XOpenDisplay(String name);
        Pointer XDefaultRootWindow(Pointer display);
        boolean XQueryTree(Pointer display, Pointer w, PointerByReference rootReturn,
                PointerByReference parentReturn, PointerByReference childrenReturn, IntByReference nChildren);
        boolean XFetchName(Pointer display, Pointer w, PointerByReference nameReturn);
        int XFree(Pointer data);
    }

    interface XExt extends Library {
        XExt INSTANCE = Native.load("Xext", XExt.class);
        boolean XShapeQueryExtension(Pointer display, IntByReference eventBase, IntByReference errorBase);
        void XShapeCombineRectangles(Pointer display, Pointer w, int shapeKind, int xOff, int yOff,
                Pointer rects, int nRects, int op, int ordering);
    }

    static final int GWL_EXSTYLE       = -20;
    static final int WS_EX_TRANSPARENT = 0x00000020;  // mouse events pass through
    static final int WS_EX_TOOLWINDOW  = 0x00000080;  // no Alt-Tab entry
    static final int WS_EX_LAYERED     = 0x00080000;  // required for alpha

    static final int SHAPE_INPUT = 1;   // XShape: manipulate the input region
    static final int SHAPE_SET   = 0;   // XShape: replace the region

    static Pointer x11Display;
    static final Map<Frame, Pointer> x11WindowCache = new HashMap<>();

    static void initNative() {
        try {
            if (isWindows) initWin32();
            else if (isLinux) initX11();
            else {
                System.err.println("[ScreenDimmer] unsupported OS: " + OS);
                nativeReady = false;
            }
        } catch (Throwable t) {
            System.err.println("[ScreenDimmer] native init failed: " + t);
            nativeReady = false;
        }
    }

    static void initWin32() {
        nativeReady = User32.INSTANCE != null;
    }

    /**
     * Linux (X11 or XWayland): click-through is done by giving the overlay an
     * empty input region via the XShape extension. Experimental - if the
     * extension is missing, dimming stays off so the overlay can never block
     * the mouse.
     */
    static void initX11() {
        x11Display = X11.INSTANCE.XOpenDisplay(null);
        if (x11Display == null) {
            System.err.println("[ScreenDimmer] could not open X display (Wayland without XWayland?)");
            nativeReady = false;
            return;
        }
        IntByReference eventBase = new IntByReference(), errorBase = new IntByReference();
        boolean shape = XExt.INSTANCE.XShapeQueryExtension(x11Display, eventBase, errorBase);
        if (!shape) System.err.println("[ScreenDimmer] XShape extension unavailable - overlays would block the mouse.");
        nativeReady = shape;
    }

    /** Depth-first search of the X window tree for our overlay by title. */
    static Pointer findX11Window(Pointer start, String title, int depth) {
        PointerByReference kidsRef = new PointerByReference();
        IntByReference count = new IntByReference();
        if (!X11.INSTANCE.XQueryTree(x11Display, start, null, null, kidsRef, count)) return null;
        Pointer kids = kidsRef.getValue();
        Pointer found = null;
        for (int i = 0; i < count.getValue() && found == null; i++) {
            Pointer win = new Pointer(kids.getLong((long) i * Native.LONG_SIZE));
            PointerByReference nameRef = new PointerByReference();
            if (X11.INSTANCE.XFetchName(x11Display, win, nameRef)) {
                Pointer namePtr = nameRef.getValue();
                if (namePtr != null) {
                    if (title.equals(namePtr.getString(0))) found = win;
                    X11.INSTANCE.XFree(namePtr);
                }
            }
            if (found == null && depth > 1) found = findX11Window(win, title, depth - 1);
        }
        if (kids != null) X11.INSTANCE.XFree(kids);
        return found;
    }

    static void applyClickThrough(Frame f) {
        if (!nativeReady) return;
        if (isWindows) win32ClickThrough(f);
        else if (isLinux) x11ClickThrough(f);
    }

    static void win32ClickThrough(Frame f) {
        try {
            Pointer hwnd = User32.INSTANCE.FindWindow(null, f.getTitle());
            if (hwnd == null) {
                System.err.println("[ScreenDimmer] overlay window not found: " + f.getTitle());
                return;
            }
            int ex = User32.INSTANCE.GetWindowLong(hwnd, GWL_EXSTYLE);
            User32.INSTANCE.SetWindowLong(hwnd, GWL_EXSTYLE,
                    ex | WS_EX_TRANSPARENT | WS_EX_LAYERED | WS_EX_TOOLWINDOW);
        } catch (Throwable t) {
            System.err.println("[ScreenDimmer] click-through failed: " + t);
        }
    }

    static void x11ClickThrough(Frame f) {
        try {
            Pointer win = x11WindowCache.get(f);
            if (win == null) {
                win = findX11Window(X11.INSTANCE.XDefaultRootWindow(x11Display), f.getTitle(), 3);
                if (win == null) {
                    System.err.println("[ScreenDimmer] overlay window not found: " + f.getTitle());
                    return;
                }
                x11WindowCache.put(f, win);
            }
            // zero rectangles with SHAPE_SET: an empty input region, so every
            // click falls through to the window below
            XExt.INSTANCE.XShapeCombineRectangles(x11Display, win, SHAPE_INPUT, 0, 0, null, 0, SHAPE_SET, 0);
        } catch (Throwable t) {
            System.err.println("[ScreenDimmer] click-through failed: " + t);
        }
    }

    // -------------------------------------------------------- overlay windows
    static void applyDim() {
        if (dimActive()) {
            if (overlaysStale()) rebuildOverlays();
            float alpha = dimPercent / 100f;
            for (Frame f : overlays) {
                f.setOpacity(alpha);
                if (!f.isVisible()) f.setVisible(true);
                applyClickThrough(f);
            }
        } else {
            for (Frame f : overlays) f.setVisible(false);
        }
        if (tray != null) {
            tray.setImage(icon(dimActive()));
            tray.setToolTip("Screen Dimmer v" + VERSION + " - " + (dimActive() ? dimPercent + "%" : "off") + " (right-click: menu)");
        }
        scheduleSave();
    }

    static boolean overlaysStale() {
        GraphicsDevice[] devs = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
        if (overlays.size() != devs.length) return true;
        for (int i = 0; i < devs.length; i++)
            if (!overlays.get(i).getBounds().equals(devs[i].getDefaultConfiguration().getBounds())) return true;
        return false;
    }

    static void rebuildOverlays() {
        for (Frame f : overlays) f.dispose();
        overlays.clear();
        x11WindowCache.clear();
        GraphicsDevice[] devs = GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
        int n = 0;
        for (GraphicsDevice dev : devs) {
            Frame f = new Frame("ScreenDimmerOverlay#" + (n++));
            f.setUndecorated(true);
            f.setType(Window.Type.UTILITY);
            f.setFocusableWindowState(false);   // never takes focus, no taskbar button
            f.setAutoRequestFocus(false);
            f.setAlwaysOnTop(true);
            f.setBackground(Color.BLACK);
            f.setBounds(dev.getDefaultConfiguration().getBounds());
            f.setVisible(true);
            overlays.add(f);
        }
    }

    // ------------------------------------------------------------------ tray
    static void createTray() {
        if (!SystemTray.isSupported()) {
            System.err.println("[ScreenDimmer] system tray unsupported, opening control window instead.");
            showFallbackWindow();
            return;
        }
        tray = new TrayIcon(icon(dimActive()), "Screen Dimmer");
        tray.setImageAutoSize(true);
        tray.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) showMenu(e.getXOnScreen(), e.getYOnScreen());
                else showPopup(e.getXOnScreen(), e.getYOnScreen());
            }
        });
        try {
            SystemTray.getSystemTray().add(tray);
        } catch (AWTException ex) {
            System.err.println("[ScreenDimmer] tray icon failed: " + ex);
            showFallbackWindow();
        }
    }

    /** Crescent-moon tray icon, drawn in code so no asset files are needed. */
    static BufferedImage icon(boolean on) {
        int s = 32;
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(on ? ACCENT : ICON_OFF);
        g.fill(moonShape(15, 16.5, 10.5));
        g.dispose();
        return img;
    }

    // ----------------------------------------------------------- dark popup
    static JPanel buildContent() {
        initTheme();
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(panelColor());
        root.setBorder(BorderFactory.createLineBorder(borderColor()));

        Font titleFont = root.getFont().deriveFont(Font.BOLD, 13f);
        JLabel title = new JLabel("Screen Dimmer");
        title.setFont(titleFont);

        JLabel pct = new JLabel(dimPercent + "%", SwingConstants.RIGHT);
        pct.setForeground(ACCENT);
        pct.setFont(titleFont);

        // cross in the top-right corner: quits the app completely
        JButton quitBtn = new JButton("\u00D7");
        quitBtn.putClientProperty("JButton.buttonType", "toolBarButton");
        quitBtn.setFocusable(false);
        quitBtn.setToolTipText("Quit Screen Dimmer");
        quitBtn.setMargin(new Insets(0, 0, 0, 0));
        quitBtn.setPreferredSize(new Dimension(26, 22));
        quitBtn.addActionListener(e -> quit());

        JPanel headRight = new JPanel(new BorderLayout(4, 0));
        headRight.setOpaque(false);
        headRight.add(pct, BorderLayout.CENTER);
        headRight.add(quitBtn, BorderLayout.EAST);

        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.setBorder(BorderFactory.createEmptyBorder(8, 12, 6, 0));
        head.add(title, BorderLayout.WEST);
        head.add(headRight, BorderLayout.EAST);

        JSlider slider = new JSlider(0, MAX_DIM, dimPercent);
        slider.setOpaque(false);

        Glyph sun = new Glyph(false, dimPercent == 0, () -> slider.setValue(0));       // sun: no dimming
        Glyph moon = new Glyph(true, dimPercent >= MAX_DIM, () -> slider.setValue(MAX_DIM)); // moon: max dimming

        slider.addChangeListener(e -> {
            int v = slider.getValue();
            sun.setLit(v == 0);
            moon.setLit(v >= MAX_DIM);
            if (v != dimPercent) {
                setDim(v);
                pct.setText(v + "%");
            }
        });

        // while dimming is disabled, the slider row is hatched over like a
        // blocked-off parking spot; the rest of the popup stays normal
        JPanel row = new JPanel(new BorderLayout(6, 0)) {
            @Override protected void paintChildren(Graphics g0) {
                super.paintChildren(g0);
                if (enabled) return;
                Graphics2D g = (Graphics2D) g0;
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(new Color(0, 0, 0, 45));
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(new Color(ICON_OFF.getRed(), ICON_OFF.getGreen(), ICON_OFF.getBlue(), 90));
                g.setStroke(new BasicStroke(2f));
                int w = getWidth(), h = getHeight();
                for (int x = -h; x < w; x += 13) g.drawLine(x, h, x + h, 0);
            }
        };
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));
        row.add(sun, BorderLayout.WEST);
        row.add(slider, BorderLayout.CENTER);
        row.add(moon, BorderLayout.EAST);

        JButton toggle = new JButton();
        toggle.setAlignmentX(Component.LEFT_ALIGNMENT);
        toggle.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        toggle.addActionListener(e -> {
            setEnabledDim(!enabled);
            applyDisabledState(root, slider, sun, moon, toggle);
        });

        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(BorderFactory.createEmptyBorder(4, 14, 14, 14));
        body.add(row);
        body.add(Box.createVerticalStrut(12));
        body.add(toggle);

        root.add(head, BorderLayout.NORTH);
        root.add(body, BorderLayout.CENTER);
        applyDisabledState(root, slider, sun, moon, toggle);
        return root;
    }

    /**
     * Reflects the enabled/disabled state in the popup: while disabled, the
     * slider row (slider + sun + moon) grays out and is hatched over, while
     * the toggle stays clickable so dimming can be re-enabled in one click.
     */
    static void applyDisabledState(JPanel root, JSlider slider, Glyph sun, Glyph moon, JButton toggle) {
        boolean on = enabled;
        slider.setEnabled(on);
        sun.setEnabled(on);
        sun.setLit(on && dimPercent == 0);
        moon.setEnabled(on);
        moon.setLit(on && dimPercent >= MAX_DIM);
        toggle.setText(on ? "Disable" : "Enable");
        toggle.setBackground(on ? ACCENT : UIManager.getColor("Button.background"));
        toggle.setForeground(on ? ACCENT_TX : null);
        root.repaint();
    }

    /** Shared shell for the popup and the tray menu: dark, rounded, topmost, closes on focus loss / Esc. */
    static JDialog newPopupWindow() {
        JDialog w = new JDialog((Dialog) null);
        w.setUndecorated(true);
        w.setAlwaysOnTop(true);              // must sit above our own topmost overlay
        w.setModal(false);
        w.setBackground(panelColor());
        return w;
    }

    static void finishPopupWindow(JDialog w, int x, int y) {
        w.pack();
        Dimension d = w.getSize();
        Rectangle scr = screenAt(x, y).getDefaultConfiguration().getBounds();
        int px = Math.max(scr.x + 8, Math.min(x - d.width / 2, scr.x + scr.width - d.width - 8));
        int py = y - d.height - 14;
        if (py < scr.y + 8) py = y + 18;
        py = Math.min(py, scr.y + scr.height - d.height - 52);
        if (py < scr.y + 8) py = scr.y + 8;
        w.setLocation(px, py);
        w.setVisible(true);
        w.getRootPane().revalidate();
        w.getRootPane().repaint();

        w.addWindowFocusListener(new WindowAdapter() {
            @Override public void windowLostFocus(WindowEvent e) { w.dispose(); }
        });
        JRootPane rp = w.getRootPane();
        rp.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "sdClose");
        rp.getActionMap().put("sdClose", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { w.dispose(); }
        });
    }

    static void showPopup(int x, int y) {
        initTheme();
        JDialog w = newPopupWindow();
        w.setContentPane(buildContent());
        finishPopupWindow(w, x, y);
    }

    /** Small dark menu on tray right-click; toggle and quit live here so the popup stays minimal. */
    static void showMenu(int x, int y) {
        initTheme();
        JDialog w = newPopupWindow();
        JPanel root = new JPanel();
        root.setBackground(panelColor());
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderColor()),
                BorderFactory.createEmptyBorder(6, 6, 6, 6)));
        JButton toggleItem = menuItem(enabled ? "Disable dimming" : "Enable dimming", () -> {
            setEnabledDim(!enabled);
            w.dispose();
        });
        JButton quitItem = menuItem("Quit Screen Dimmer", () -> {
            w.dispose();
            quit();
        });
        root.add(toggleItem);
        root.add(quitItem);
        w.setContentPane(root);
        finishPopupWindow(w, x, y);
    }

    /** FlatLaf borderless button styled as a menu row. */
    static JButton menuItem(String text, Runnable onClick) {
        JButton b = new JButton(text);
        b.putClientProperty("JButton.buttonType", "toolBarButton");
        b.setHorizontalAlignment(SwingConstants.LEFT);
        b.setFocusable(false);
        b.setAlignmentX(Component.LEFT_ALIGNMENT);
        b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        b.addActionListener(e -> onClick.run());
        return b;
    }

    /** Used only when the system tray is unavailable. */
    static void showFallbackWindow() {
        JFrame f = new JFrame("Screen Dimmer");
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        f.setAlwaysOnTop(true);
        f.setResizable(false);
        f.setContentPane(buildContent());
        f.pack();
        f.setLocationRelativeTo(null);
        f.setVisible(true);
    }

    static GraphicsDevice screenAt(int x, int y) {
        for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            if (d.getDefaultConfiguration().getBounds().contains(x, y)) return d;
        }
        return GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
    }

    // ------------------------------------------------------- custom widgets
    /** Vector-painted sun/moon anchor beside the slider; doubles as a click-to-jump control. */
    static class Glyph extends JComponent {
        private final boolean moon;
        private final Runnable onClick;
        private boolean lit;

        Glyph(boolean moon, boolean lit, Runnable onClick) {
            this.moon = moon;
            this.lit = lit;
            this.onClick = onClick;
            setPreferredSize(new Dimension(24, 24));
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            enableEvents(AWTEvent.MOUSE_EVENT_MASK);
        }

        void setLit(boolean lit) {
            if (lit != this.lit) {
                this.lit = lit;
                repaint();
            }
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (moon) drawMoon(g, 12, 12, lit ? ACCENT : ICON_OFF);
            else drawSun(g, 12, 12, lit ? SUN : ICON_OFF);
        }

        @Override protected void processMouseEvent(MouseEvent e) {
            if (e.getID() == MouseEvent.MOUSE_PRESSED && isEnabled()) onClick.run();
            super.processMouseEvent(e);
        }
    }

    static void drawSun(Graphics2D g, int cx, int cy, Color c) {
        g.setColor(c);
        g.fillOval(cx - 5, cy - 5, 10, 10);
        g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            g.drawLine(cx + (int) Math.round(7.5 * Math.cos(a)), cy + (int) Math.round(7.5 * Math.sin(a)),
                       cx + (int) Math.round(10.5 * Math.cos(a)), cy + (int) Math.round(10.5 * Math.sin(a)));
        }
    }

    /** Crescent built by boolean subtraction of two circles - a real shape, no transparent hole punching. */
    static Shape moonShape(double cx, double cy, double r) {
        Area moon = new Area(new Ellipse2D.Double(cx - r, cy - r, r * 2, r * 2));
        double br = r * 0.86;
        double bx = cx + r * 0.62, by = cy - r * 0.45;
        moon.subtract(new Area(new Ellipse2D.Double(bx - br, by - br, br * 2, br * 2)));
        return moon;
    }

    static void drawMoon(Graphics2D g, int cx, int cy, Color c) {
        g.setColor(c);
        g.fill(moonShape(cx, cy, 7));
    }

    // ----------------------------------------------------------- persistence
    static void setDim(int v) {
        dimPercent = Math.max(0, Math.min(MAX_DIM, v));
        applyDim();
    }

    static void setEnabledDim(boolean b) {
        enabled = b;
        applyDim();
    }

    static void quit() {
        saveNow();
        if (tray != null) SystemTray.getSystemTray().remove(tray);
        System.exit(0);
    }

    static void load() {
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(cfgFile)) { p.load(in); } catch (Exception ignored) {}
        int v;
        try { v = Integer.parseInt(p.getProperty("dim", "30").trim()); } catch (Exception e) { v = 30; }
        dimPercent = Math.max(0, Math.min(MAX_DIM, v));
        enabled = !"false".equalsIgnoreCase(p.getProperty("enabled", "true").trim());
    }

    static void scheduleSave() {
        if (saveTimer == null) {
            saveTimer = new javax.swing.Timer(400, e -> saveNow());
            saveTimer.setRepeats(false);
        }
        saveTimer.restart();
    }

    static void saveNow() {
        Properties p = new Properties();
        p.setProperty("dim", String.valueOf(dimPercent));
        p.setProperty("enabled", String.valueOf(enabled));
        try (OutputStream out = new FileOutputStream(cfgFile)) { p.store(out, "ScreenDimmer settings"); }
        catch (Exception ignored) {}
    }
}
