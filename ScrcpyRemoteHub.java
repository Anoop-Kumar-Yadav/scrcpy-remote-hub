
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.*;
import java.awt.event.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.text.*;

// =============================================================================
//  ScrcpyRemoteHub — Premium Edition v2 (Systematic Structure)
// =============================================================================
public class ScrcpyRemoteHub extends JFrame {

    // ── Systematic Paths ──────────────────────────────────────────────────────
    private static final String TOOLS_DIR = "tools" + File.separator;
    private static final String ADB = TOOLS_DIR + "adb.exe";
    private static final String SCRCPY = TOOLS_DIR + "scrcpy.exe";

    private static final String DATA_DIR = "data" + File.separator;
    private static final String SHOT_DIR = "Screenshots" + File.separator;
    private static final String REC_DIR = "Recordings" + File.separator;

    // ── Palette ───────────────────────────────────────────────────────────────
    static final Color BG_BASE = new Color(10, 13, 20);
    static final Color BG_SURFACE = new Color(17, 22, 35);
    static final Color BG_ELEVATED = new Color(24, 30, 48);
    static final Color BG_BORDER = new Color(38, 48, 72);
    static final Color TXT_PRI = new Color(220, 228, 248);
    static final Color TXT_MUT = new Color(108, 120, 156);
    static final Color CYAN = new Color(34, 211, 238);
    static final Color CYAN_DIM = new Color(20, 130, 150);
    static final Color GREEN = new Color(52, 211, 153);
    static final Color RED = new Color(248, 92, 92);
    static final Color AMBER = new Color(251, 189, 35);
    static final Color PURPLE = new Color(167, 139, 250);
    static final Color ORANGE = new Color(251, 146, 60);

    // ── Fonts ─────────────────────────────────────────────────────────────────
    static final Font F_TITLE = new Font("Segoe UI", Font.BOLD, 18);
    static final Font F_UI = new Font("Segoe UI", Font.PLAIN, 12);
    static final Font F_UI_B = new Font("Segoe UI", Font.BOLD, 12);
    static final Font F_LBL = new Font("Segoe UI", Font.BOLD, 11);
    static final Font F_SMALL = new Font("Segoe UI", Font.PLAIN, 11);
    static final Font F_MONO = new Font("Consolas", Font.PLAIN, 12);
    static final Font F_MONO_B = new Font("Consolas", Font.BOLD, 11);

    // ── Size constants ────────────────────────────────────────────────────────
    static final int CTL_H = 30;   // uniform height for all combos and fields
    static final int BTN_H = 30;   // uniform height for all buttons

    // =========================================================================
    //  LOG LEVEL
    // =========================================================================
    enum LogLevel {
        INFO("INFO ", new Color(140, 180, 255)),
        OK("OK   ", new Color(52, 211, 153)),
        WARN("WARN ", new Color(251, 189, 35)),
        ERROR("ERROR", new Color(248, 92, 92)),
        CMD("CMD  ", new Color(167, 139, 250)),
        DEBUG("DEBUG", new Color(108, 120, 156));
        final String tag;
        final Color color;

        LogLevel(String t, Color c) {
            tag = t;
            color = c;
        }
    }

    // =========================================================================
    //  PROCESS MANAGER
    // =========================================================================
    static class ProcessManager {

        private final Map<String, Process> reg = new ConcurrentHashMap<>();
        private final ScheduledExecutorService reaper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "reaper");
            t.setDaemon(true);
            return t;
        });

        void register(String name, Process p) {
            reg.put(name, p);
            reaper.scheduleAtFixedRate(
                    () -> reg.entrySet().removeIf(e -> !e.getValue().isAlive()),
                    5, 5, TimeUnit.SECONDS);
        }

        boolean stop(String name, long ms) {
            Process p = reg.remove(name);
            if (p == null || !p.isAlive()) {
                return false;
            }
            p.destroy();
            try {
                if (!p.waitFor(ms, TimeUnit.MILLISECONDS)) {
                    p.destroyForcibly();

                }
            } catch (InterruptedException ignored) {
                p.destroyForcibly();
            }
            return true;
        }

        void stopAll() {
            reg.forEach((n, p) -> {
                if (p.isAlive()) {
                    p.destroyForcibly();

                }
            });
            reg.clear();
        }
    }

    // =========================================================================
    //  COMMAND RUNNER
    // =========================================================================
    static class CmdResult {

        final String stdout, stderr;
        final int exitCode;
        final boolean success;

        CmdResult(String o, String e, int c) {
            stdout = o.trim();
            stderr = e.trim();
            exitCode = c;
            success = (c == 0);
        }
    }

    static CmdResult exec(boolean merge, String... args) {
        StringBuilder out = new StringBuilder(), err = new StringBuilder();
        int code = -1;
        try {
            ProcessBuilder pb = new ProcessBuilder(args);
            pb.redirectErrorStream(merge);
            Process p = pb.start();
            Thread tO = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String l;
                    while ((l = r.readLine()) != null) {
                        out.append(l).append('\n');
                    }
                } catch (IOException ignored) {
                }
            }, "out-drain");
            Thread tE = new Thread(() -> {
                if (!merge) try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream()))) {
                    String l;
                    while ((l = r.readLine()) != null) {
                        err.append(l).append('\n');
                    }
                } catch (IOException ignored) {
                }
            }, "err-drain");
            tO.start();
            tE.start();
            code = p.waitFor();
            tO.join(3000);
            tE.join(3000);
        } catch (Exception e) {
            err.append(e.getMessage());
        }
        return new CmdResult(out.toString(), err.toString(), code);
    }

    static String execSilent(String... args) {
        return exec(true, args).stdout;
    }

    // =========================================================================
    //  GLOW BUTTON
    // =========================================================================
    static class GlowButton extends JButton {

        private final Color accent;
        private float glow = 0f;
        private javax.swing.Timer timer;

        GlowButton(String text, Color accent) {
            super(text);
            this.accent = accent;
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setForeground(Color.WHITE);
            setFont(F_UI_B);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setBorder(new EmptyBorder(0, 14, 0, 14));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    animate(true);
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    animate(false);
                }
            });
        }

        void animate(boolean in) {
            if (timer != null) {
                timer.stop();
            }
            timer = new javax.swing.Timer(14, null);
            timer.addActionListener(ev -> {
                glow = in ? Math.min(1f, glow + 0.13f) : Math.max(0f, glow - 0.13f);
                repaint();
                if ((in && glow >= 1f) || (!in && glow <= 0f)) {
                    timer.stop();
                }
            });
            timer.start();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight(), arc = h;
            if (glow > 0) {
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, glow * 0.28f));
                g2.setColor(accent);
                g2.fillRoundRect(-5, -5, w + 10, h + 10, arc + 10, arc + 10);
                g2.setComposite(AlphaComposite.SrcOver);
            }
            g2.setColor(isEnabled()
                    ? new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), (int) (165 + 90 * glow))
                    : new Color(42, 50, 65));
            g2.fillRoundRect(0, 0, w, h, arc, arc);
            g2.setColor(new Color(255, 255, 255, 22));
            g2.drawLine(arc / 2, 1, w - arc / 2, 1);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    // =========================================================================
    //  VITAL LABEL  (dot + text)
    // =========================================================================
    static class VitalLabel extends JLabel {

        private Color dot;

        VitalLabel(String text, Color dot) {
            super("   " + text);
            this.dot = dot;
            setForeground(TXT_PRI);
            setFont(F_UI_B);
        }

        void setDot(Color c) {
            this.dot = c;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(dot);
            g2.fillOval(0, getHeight() / 2 - 5, 10, 10);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    // =========================================================================
    //  FORM GRID  — the layout engine
    // =========================================================================
    static class FormGrid extends JPanel {

        private static final int LABEL_W = 94;
        private static final int H_GAP = 7;
        private static final int V_PAD = 5;
        private static final int GLUE_COL = 500;   // far-right weight absorber

        private int currentRow = 0;

        FormGrid() {
            setLayout(new GridBagLayout());
            setOpaque(false);
        }

        void row(String labelText, Component... controls) {
            // --- label ---
            JLabel lbl = new JLabel(labelText != null ? labelText : "");
            lbl.setFont(F_SMALL);
            lbl.setForeground(TXT_MUT);
            lbl.setHorizontalAlignment(SwingConstants.RIGHT);
            lbl.setPreferredSize(new Dimension(LABEL_W, CTL_H));
            lbl.setMinimumSize(new Dimension(LABEL_W, CTL_H));

            GridBagConstraints lc = gbc(0, currentRow);
            lc.anchor = GridBagConstraints.LINE_END;
            lc.insets = new Insets(V_PAD, 8, V_PAD, H_GAP);
            add(lbl, lc);

            // --- controls ---
            for (int i = 0; i < controls.length; i++) {
                Component c = controls[i];
                GridBagConstraints cc = gbc(i + 1, currentRow);
                cc.anchor = GridBagConstraints.LINE_START;
                cc.insets = new Insets(V_PAD, (i == 0 ? 0 : H_GAP), V_PAD, 0);

                if (c instanceof JTextField) {
                    cc.fill = GridBagConstraints.HORIZONTAL;
                    cc.weightx = 1.0;
                } else {
                    cc.fill = GridBagConstraints.NONE;
                    cc.weightx = 0;
                }
                add(c, cc);
            }
            currentRow++;
        }

        void fullRow(Component... controls) {
            JPanel inner = new JPanel(new FlowLayout(FlowLayout.LEFT, H_GAP, 0));
            inner.setOpaque(false);
            for (Component c : controls) {
                inner.add(c);
            }

            GridBagConstraints fc = gbc(0, currentRow);
            fc.gridwidth = GridBagConstraints.REMAINDER;
            fc.anchor = GridBagConstraints.LINE_START;
            fc.fill = GridBagConstraints.NONE;
            fc.weightx = 0;
            fc.insets = new Insets(V_PAD, 8, V_PAD, 8);
            add(inner, fc);
            currentRow++;
        }

        void divider() {
            JSeparator sep = new JSeparator();
            sep.setForeground(BG_BORDER);
            GridBagConstraints dc = gbc(0, currentRow);
            dc.gridwidth = GridBagConstraints.REMAINDER;
            dc.fill = GridBagConstraints.HORIZONTAL;
            dc.weightx = 1.0;
            dc.insets = new Insets(1, 12, 1, 12);
            add(sep, dc);
            currentRow++;
        }

        void seal() {
            GridBagConstraints gc = gbc(GLUE_COL, 0);
            gc.weightx = 1.0;
            gc.fill = GridBagConstraints.HORIZONTAL;
            add(new JPanel() {
                {
                    setOpaque(false);
                    setPreferredSize(new Dimension(0, 0));
                }
            }, gc);
        }

        private static GridBagConstraints gbc(int x, int y) {
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = x;
            c.gridy = y;
            c.fill = GridBagConstraints.NONE;
            c.weightx = 0;
            c.weighty = 0;
            c.anchor = GridBagConstraints.LINE_START;
            return c;
        }
    }

    // =========================================================================
    //  CARD  — rounded surface with accent stripe + title
    // =========================================================================
    static class Card extends JPanel {

        private final Color stripe;
        private final String title;

        Card(String title, Color stripe) {
            this.title = title;
            this.stripe = stripe;
            setOpaque(false);
            setLayout(new BorderLayout());
            setBorder(new EmptyBorder(22, 14, 12, 14));
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight(), arc = 14;
            g2.setColor(BG_SURFACE);
            g2.fillRoundRect(0, 0, w - 1, h - 1, arc, arc);
            // accent stripe clipped to the rounded rect
            Shape clip = new java.awt.geom.RoundRectangle2D.Float(0, 0, w - 1, h - 1, arc, arc);
            g2.setClip(clip);
            g2.setColor(stripe);
            g2.fillRect(0, 0, 4, h);
            g2.setClip(null);
            // border
            g2.setColor(BG_BORDER);
            g2.setStroke(new BasicStroke(1f));
            g2.drawRoundRect(0, 0, w - 1, h - 1, arc, arc);
            // title text
            g2.setFont(F_LBL);
            g2.setColor(stripe);
            g2.drawString(title.toUpperCase(), 14, 14);
            // under-title rule
            g2.setColor(new Color(BG_BORDER.getRed(), BG_BORDER.getGreen(), BG_BORDER.getBlue(), 100));
            g2.drawLine(14, 18, w - 14, 18);
            g2.dispose();
        }
    }

    // =========================================================================
    //  SLIM SCROLLBAR
    // =========================================================================
    static class SlimSB extends javax.swing.plaf.basic.BasicScrollBarUI {

        @Override
        protected void configureScrollBarColors() {
            thumbColor = new Color(55, 70, 105);
            trackColor = BG_BASE;
            thumbDarkShadowColor = thumbHighlightColor = thumbLightShadowColor = BG_BASE;
        }

        @Override
        protected JButton createDecreaseButton(int o) {
            return stub();
        }

        @Override
        protected JButton createIncreaseButton(int o) {
            return stub();
        }

        static JButton stub() {
            JButton b = new JButton();
            b.setPreferredSize(new Dimension(0, 0));
            return b;
        }

        @Override
        protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
            if (r.isEmpty()) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(thumbColor);
            g2.fillRoundRect(r.x + 2, r.y + 2, r.width - 4, r.height - 4, 8, 8);
            g2.dispose();
        }

        @Override
        protected void paintTrack(Graphics g, JComponent c, Rectangle r) {
            g.setColor(trackColor);
            g.fillRect(r.x, r.y, r.width, r.height);
        }
    }

    // =========================================================================
    //  FACTORY HELPERS — every control sized uniformly
    // =========================================================================
    static JComboBox<String> combo(int w, String... items) {
        JComboBox<String> c = new JComboBox<>(items);
        c.setBackground(BG_ELEVATED);
        c.setForeground(TXT_PRI);
        c.setFont(F_UI);
        Dimension d = new Dimension(w, CTL_H);
        c.setPreferredSize(d);
        c.setMinimumSize(d);
        c.setMaximumSize(d);
        c.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BG_BORDER, 1, true),
                BorderFactory.createEmptyBorder(0, 6, 0, 0)));
        return c;
    }

    static JTextField field(String init, int w) {
        JTextField f = new JTextField(init);
        f.setBackground(BG_ELEVATED);
        f.setForeground(TXT_PRI);
        f.setCaretColor(CYAN);
        f.setFont(F_UI);
        f.setPreferredSize(new Dimension(w, CTL_H));
        f.setMinimumSize(new Dimension(Math.min(w, 80), CTL_H));
        f.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BG_BORDER, 1, true),
                BorderFactory.createEmptyBorder(0, 8, 0, 8)));
        return f;
    }

    static JTextField roField(String init) {
        JTextField f = field(init, 160);
        f.setEditable(false);
        f.setForeground(TXT_MUT);
        return f;
    }

    static GlowButton btn(String text, Color accent, int minW) {
        GlowButton b = new GlowButton(text, accent);
        Dimension d = new Dimension(minW, BTN_H);
        b.setPreferredSize(d);
        b.setMinimumSize(d);
        b.setMaximumSize(new Dimension(minW, BTN_H));
        return b;
    }

    static JCheckBox check(String text) {
        JCheckBox c = new JCheckBox(text);
        c.setBackground(BG_SURFACE);
        c.setForeground(TXT_PRI);
        c.setFont(F_UI);
        c.setFocusPainted(false);
        return c;
    }

    static JLabel subLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(F_SMALL);
        l.setForeground(TXT_MUT);
        return l;
    }

    static Component hgap(int w) {
        return Box.createRigidArea(new Dimension(w, 0));
    }

    static Component vgap(int h) {
        return Box.createRigidArea(new Dimension(0, h));
    }

    // =========================================================================
    //  INSTANCE STATE
    // =========================================================================
    private final ProcessManager procMgr = new ProcessManager();
    private final Properties profiles = new Properties();
    private final File PROFS = new File(DATA_DIR + "scrcpy_profiles.properties");

    private JComboBox<String> cmbUsb, cmbProfiles;
    private JTextField txIp, txFolder;
    private JCheckBox chkAutoReconnect, chkHideVpn, chkVerbose,
            chkPresentation, chkNoAudio, chkMutePhone;
    private JComboBox<String> cmbBitrate, cmbRes, cmbFps;
    private VitalLabel lblBattery, lblTemp, lblScreen;
    private JLabel lblConnStatus;
    private GlowButton btnLaunch, btnConnect, btnDisconnect,
            btnEnableTcp, btnRefreshUsb,
            btnSaveProf, btnDelProf,
            btnScreenshot, btnRecordMic,
            btnSnapFront, btnSnapBack;

    private String saveFolder = new File(SHOT_DIR).getAbsolutePath();
    private final AtomicBoolean micActive = new AtomicBoolean(false);

    // =========================================================================
    //  CONSTRUCTOR
    // =========================================================================
    public ScrcpyRemoteHub() {
        initFolders();
        checkBinaries();

        setTitle("ScrcpyHub  •  Remote Device Console");
        setSize(960, 1060);
        setMinimumSize(new Dimension(880, 720));
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        getContentPane().setBackground(BG_BASE);

        Runtime.getRuntime().addShutdownHook(new Thread(procMgr::stopAll, "shutdown-hook"));

        loadProfiles();
        buildUi();
        startVitalsMonitor();
        refreshUsb();
    }

    private void initFolders() {
        new File(TOOLS_DIR).mkdirs();
        new File(DATA_DIR).mkdirs();
        new File(SHOT_DIR).mkdirs();
        new File(REC_DIR).mkdirs();
    }

    private void checkBinaries() {
        if (!new File(ADB).exists() || !new File(SCRCPY).exists()) {
            JOptionPane.showMessageDialog(null,
                    "ERROR: Binaries missing in /tools/ folder!\nPlease place adb.exe and scrcpy.exe inside the 'tools' subfolder.",
                    "Setup Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // =========================================================================
    //  UI BUILD
    // =========================================================================
    private void buildUi() {
        setLayout(new BorderLayout(0, 0));
        add(buildHeader(), BorderLayout.NORTH);
        add(buildScroll(), BorderLayout.CENTER);
        add(buildConsole(), BorderLayout.SOUTH);
    }

    // ── Header ────────────────────────────────────────────────────────────────
    private JPanel buildHeader() {
        JPanel h = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setPaint(new GradientPaint(0, 0, new Color(8, 24, 52), getWidth(), 0, new Color(4, 12, 28)));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setColor(CYAN);
                g2.setStroke(new BasicStroke(2f));
                g2.drawLine(0, getHeight() - 1, getWidth(), getHeight() - 1);
                g2.dispose();
            }
        };
        h.setBorder(new EmptyBorder(13, 20, 13, 20));
        h.setPreferredSize(new Dimension(0, 64));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        left.setOpaque(false);
        JLabel ico = new JLabel("◈  ");
        ico.setFont(new Font("Segoe UI", Font.BOLD, 26));
        ico.setForeground(CYAN);
        JPanel ts = new JPanel();
        ts.setLayout(new BoxLayout(ts, BoxLayout.Y_AXIS));
        ts.setOpaque(false);
        JLabel t1 = new JLabel("SCRCPY REMOTE HUB");
        t1.setFont(F_TITLE);
        t1.setForeground(TXT_PRI);
        JLabel t2 = new JLabel("Enterprise Device Console  •  ADB / Tailscale");
        t2.setFont(F_SMALL);
        t2.setForeground(TXT_MUT);
        ts.add(t1);
        ts.add(t2);
        left.add(ico);
        left.add(ts);
        h.add(left, BorderLayout.WEST);

        lblConnStatus = new JLabel(" ● DISCONNECTED ");
        lblConnStatus.setFont(F_LBL);
        styleStatus(false);
        h.add(lblConnStatus, BorderLayout.EAST);
        return h;
    }

    // ── Scrollable content ────────────────────────────────────────────────────
    private JScrollPane buildScroll() {
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(BG_BASE);
        content.setBorder(new EmptyBorder(14, 16, 14, 16));

        content.add(buildVitalsBar());
        content.add(vgap(10));
        content.add(buildUsbCard());
        content.add(vgap(10));
        content.add(buildWirelessCard());
        content.add(vgap(10));
        content.add(buildOptCard());
        content.add(vgap(10));
        content.add(buildToolsCard());
        content.add(vgap(10));
        content.add(buildDropZone());
        content.add(vgap(12));
        content.add(buildLaunchRow());
        content.add(vgap(8));

        JScrollPane sp = new JScrollPane(content);
        sp.setBorder(null);
        sp.setBackground(BG_BASE);
        sp.getViewport().setBackground(BG_BASE);
        sp.getVerticalScrollBar().setUnitIncrement(20);
        sp.getVerticalScrollBar().setUI(new SlimSB());
        return sp;
    }

    // ── Vitals bar ────────────────────────────────────────────────────────────
    private JPanel buildVitalsBar() {
        JPanel bar = new JPanel(new GridLayout(1, 3, 0, 0));
        bar.setBackground(BG_BASE);
        bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        bar.setPreferredSize(new Dimension(0, 32));

        lblBattery = new VitalLabel("Battery: —", TXT_MUT);
        lblTemp = new VitalLabel("Temp: —", TXT_MUT);
        lblScreen = new VitalLabel("Screen: —", TXT_MUT);

        bar.add(centredPanel(lblBattery));
        bar.add(centredPanel(lblTemp));
        bar.add(centredPanel(lblScreen));
        return bar;
    }

    private static JPanel centredPanel(Component c) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 4));
        p.setOpaque(false);
        p.add(c);
        return p;
    }

    // ── USB Card ──────────────────────────────────────────────────────────────
    private Card buildUsbCard() {
        Card card = new Card("① USB Physical Setup", CYAN);

        cmbUsb = combo(200, "Scanning...");
        btnRefreshUsb = btn("⟳  Refresh", CYAN_DIM, 100);
        btnRefreshUsb.addActionListener(e -> refreshUsb());
        btnEnableTcp = btn("Enable TCP/IP  :5555", GREEN, 182);
        btnEnableTcp.setEnabled(false);
        btnEnableTcp.addActionListener(e -> enableTcp());

        FormGrid fg = new FormGrid();
        fg.row("USB Serial:", cmbUsb, btnRefreshUsb, btnEnableTcp);
        fg.seal();
        card.add(fg, BorderLayout.CENTER);
        return card;
    }

    // ── Wireless Card ─────────────────────────────────────────────────────────
    private Card buildWirelessCard() {
        Card card = new Card("② Wireless / Tailscale Connection", PURPLE);

        cmbProfiles = combo(180, "— Select —");
        cmbProfiles.addActionListener(e -> onProfileSelected());
        populateProfiles();

        btnSaveProf = btn("Save", GREEN, 72);
        btnSaveProf.addActionListener(e -> saveProfile());
        btnDelProf = btn("Delete", RED, 72);
        btnDelProf.addActionListener(e -> deleteProfile());

        txIp = field("100.", 160);
        btnConnect = btn("Connect", CYAN, 100);
        btnConnect.addActionListener(e -> connectDevice());
        btnDisconnect = btn("Disconnect All", RED, 132);
        btnDisconnect.addActionListener(e -> disconnectAll());

        chkAutoReconnect = check("Auto-Reconnect");
        chkAutoReconnect.setForeground(AMBER);

        FormGrid fg = new FormGrid();
        fg.row("Profile:", cmbProfiles, btnSaveProf, btnDelProf);
        fg.divider();
        fg.row("IP Address:", txIp, btnConnect, btnDisconnect, chkAutoReconnect);
        fg.seal();
        card.add(fg, BorderLayout.CENTER);
        return card;
    }

    // ── Optimization Card ─────────────────────────────────────────────────────
    private Card buildOptCard() {
        Card card = new Card("③ Stream Optimization", AMBER);

        cmbBitrate = combo(148, "1M — Minimal", "2M — Balanced", "4M — High", "8M — Ultra");
        cmbBitrate.setSelectedIndex(1);
        cmbRes = combo(92, "480", "720", "1024", "1080", "Max");
        cmbRes.setSelectedIndex(1);
        cmbFps = combo(68, "15", "30", "60");
        cmbFps.setSelectedIndex(1);

        chkPresentation = check("Presentation Mode");
        chkNoAudio = check("No PC Audio");
        chkMutePhone = check("Mute Phone Speaker");

        FormGrid fg = new FormGrid();
        fg.row("Bitrate:",
                cmbBitrate,
                subLabel("  Resolution:"), cmbRes,
                subLabel("  FPS:"), cmbFps);
        fg.divider();
        fg.fullRow(chkPresentation, chkNoAudio, chkMutePhone);
        fg.seal();
        card.add(fg, BorderLayout.CENTER);
        return card;
    }

    // ── Tools Card ────────────────────────────────────────────────────────────
    private Card buildToolsCard() {
        Card card = new Card("④ Remote Tools", ORANGE);

        btnScreenshot = btn("Screenshot", PURPLE, 112);
        btnRecordMic = btn("⏺  Record Mic", new Color(210, 30, 90), 122);
        btnSnapFront = btn("📸 Front Cam", ORANGE, 112);
        btnSnapBack = btn("📷 Rear Cam", ORANGE, 112);
        btnScreenshot.addActionListener(e -> takeScreenshot());
        btnRecordMic.addActionListener(e -> toggleMic());
        btnSnapFront.addActionListener(e -> snapCam("front"));
        btnSnapBack.addActionListener(e -> snapCam("back"));

        chkHideVpn = check("Hide VPN Icon");
        chkHideVpn.addActionListener(e -> toggleVpnIcon());
        chkVerbose = check("Verbose Logging");

        txFolder = roField(saveFolder);
        GlowButton btnBrowse = btn("Browse…", CYAN_DIM, 88);
        btnBrowse.addActionListener(e -> chooseFolder());

        FormGrid fg = new FormGrid();
        fg.fullRow(btnScreenshot, btnRecordMic, btnSnapFront, btnSnapBack);
        fg.divider();
        fg.fullRow(chkHideVpn, chkVerbose);
        fg.divider();
        fg.row("Save folder:", txFolder, btnBrowse);
        fg.seal();
        card.add(fg, BorderLayout.CENTER);
        return card;
    }

    // ── Drop zone ─────────────────────────────────────────────────────────────
    private JPanel buildDropZone() {
        JPanel zone = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(BG_SURFACE);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
                float[] dash = {8f, 5f};
                g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0, dash, 0));
                g2.setColor(GREEN);
                g2.drawRoundRect(2, 2, getWidth() - 5, getHeight() - 5, 12, 12);
                g2.dispose();
            }
        };
        zone.setOpaque(false);
        Dimension dz = new Dimension(0, 68);
        zone.setPreferredSize(dz);
        zone.setMaximumSize(new Dimension(Integer.MAX_VALUE, 68));
        zone.setMinimumSize(dz);

        JLabel lbl = new JLabel("⬇  Drag & Drop APK here to install on active device", SwingConstants.CENTER);
        lbl.setForeground(GREEN);
        lbl.setFont(F_UI_B);
        zone.add(lbl, BorderLayout.CENTER);

        zone.setDropTarget(new DropTarget() {
            @Override
            public synchronized void drop(DropTargetDropEvent e) {
                try {
                    e.acceptDrop(DnDConstants.ACTION_COPY);
                    @SuppressWarnings("unchecked")
                    List<File> files = (List<File>) e.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    String dev = activeDevice();
                    if (dev == null) {
                        logWarn("No active device — connect first.");
                        return;
                    }
                    for (File f : files) {
                        if (f.getName().endsWith(".apk")) {
                            String path = f.getAbsolutePath(), name = f.getName();
                            logCmd(ADB + " -s " + dev + " install \"" + path + "\"");
                            new Thread(() -> {
                                logInfo("Installing " + name + "...");
                                CmdResult r = exec(true, ADB, "-s", dev, "install", path);
                                if (r.success) {
                                    logOk("APK installed: " + name);
                                } else {
                                    logError("APK install failed: " + r.stdout);
                                }
                            }, "apk-install").start();
                        } else {
                            logWarn("Skipped non-APK: " + f.getName());
                        }
                    }
                } catch (Exception ex) {
                    logError("Drop error: " + ex.getMessage());
                }
            }
        });
        return zone;
    }

    // ── Launch button ─────────────────────────────────────────────────────────
    private JPanel buildLaunchRow() {
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setOpaque(false);
        wrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));
        wrap.setPreferredSize(new Dimension(0, 52));

        btnLaunch = new GlowButton("▶   LAUNCH SCRCPY STREAM", GREEN) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth(), h = getHeight();
                g2.setColor(isEnabled() ? new Color(52, 211, 153, 200) : new Color(32, 44, 40));
                g2.fillRoundRect(0, 0, w, h, 12, 12);
                g2.setColor(new Color(255, 255, 255, 16));
                g2.drawLine(12, 1, w - 12, 1);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        btnLaunch.setFont(new Font("Segoe UI", Font.BOLD, 15));
        btnLaunch.setEnabled(false);
        btnLaunch.setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));
        btnLaunch.addActionListener(e -> launchScrcpy());
        wrap.add(btnLaunch, BorderLayout.CENTER);
        return wrap;
    }

    // ── Log console ───────────────────────────────────────────────────────────
    private JTextPane logPane;
    private StyledDocument logDoc;
    private static final SimpleDateFormat TS = new SimpleDateFormat("HH:mm:ss");

    private JPanel buildConsole() {
        logPane = new JTextPane();
        logPane.setEditable(false);
        logPane.setBackground(new Color(7, 9, 16));
        logPane.setBorder(new EmptyBorder(8, 10, 8, 10));
        logDoc = logPane.getStyledDocument();

        JScrollPane sp = new JScrollPane(logPane);
        sp.setPreferredSize(new Dimension(0, 200));
        sp.setBorder(null);
        sp.getVerticalScrollBar().setUI(new SlimSB());

        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(new Color(10, 14, 24));
        bar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, BG_BORDER));
        bar.setPreferredSize(new Dimension(0, 32));

        JLabel title = new JLabel("   CONSOLE");
        title.setFont(F_LBL);
        title.setForeground(TXT_MUT);
        bar.add(title, BorderLayout.WEST);

        GlowButton clr = btn("Clear", new Color(45, 55, 80), 62);
        clr.addActionListener(e -> {
            try {
                logDoc.remove(0, logDoc.getLength());
            } catch (BadLocationException ignored) {
            }
        });
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 1));
        right.setOpaque(false);
        right.add(clr);
        bar.add(right, BorderLayout.EAST);

        JPanel wrap = new JPanel(new BorderLayout());
        wrap.add(bar, BorderLayout.NORTH);
        wrap.add(sp, BorderLayout.CENTER);
        return wrap;
    }

    // =========================================================================
    //  LOGGING
    // =========================================================================
    private void log(LogLevel lv, String msg) {
        SwingUtilities.invokeLater(() -> {
            try {
                SimpleAttributeSet tsStyle = new SimpleAttributeSet();
                StyleConstants.setForeground(tsStyle, TXT_MUT);
                StyleConstants.setFontFamily(tsStyle, F_MONO.getFamily());
                StyleConstants.setFontSize(tsStyle, 11);
                logDoc.insertString(logDoc.getLength(), TS.format(new Date()) + "  ", tsStyle);

                SimpleAttributeSet lvStyle = new SimpleAttributeSet();
                StyleConstants.setForeground(lvStyle, lv.color);
                StyleConstants.setBold(lvStyle, true);
                StyleConstants.setFontFamily(lvStyle, F_MONO_B.getFamily());
                StyleConstants.setFontSize(lvStyle, 11);
                logDoc.insertString(logDoc.getLength(), "[" + lv.tag + "]  ", lvStyle);

                Color mc = lv == LogLevel.ERROR ? RED
                        : lv == LogLevel.OK ? GREEN
                                : lv == LogLevel.WARN ? AMBER : TXT_PRI;
                SimpleAttributeSet bodyStyle = new SimpleAttributeSet();
                StyleConstants.setForeground(bodyStyle, mc);
                StyleConstants.setFontFamily(bodyStyle, F_MONO.getFamily());
                StyleConstants.setFontSize(bodyStyle, 12);
                logDoc.insertString(logDoc.getLength(), msg + "\n", bodyStyle);
                logPane.setCaretPosition(logDoc.getLength());
            } catch (BadLocationException ignored) {
            }
        });
    }

    void logInfo(String m) {
        log(LogLevel.INFO, m);
    }

    void logOk(String m) {
        log(LogLevel.OK, m);
    }

    void logWarn(String m) {
        log(LogLevel.WARN, m);
    }

    void logError(String m) {
        log(LogLevel.ERROR, m);
    }

    void logCmd(String m) {
        log(LogLevel.CMD, m);
    }

    void logDebug(String m) {
        log(LogLevel.DEBUG, m);
    }

    // =========================================================================
    //  ACTIONS
    // =========================================================================
    private void refreshUsb() {
        cmbUsb.removeAllItems();
        cmbUsb.addItem("Scanning...");
        btnRefreshUsb.setEnabled(false);
        logInfo("Scanning for USB ADB devices...");
        new Thread(() -> {
            CmdResult r = exec(false, ADB, "devices");
            List<String> found = new ArrayList<>();
            for (String ln : r.stdout.split("\n")) {
                ln = ln.trim();
                if (ln.endsWith("device") && !ln.contains(":5555")) {
                    found.add(ln.split("\\s+")[0]);
                }
            }
            SwingUtilities.invokeLater(() -> {
                cmbUsb.removeAllItems();
                if (found.isEmpty()) {
                    cmbUsb.addItem("No USB device found");
                    btnEnableTcp.setEnabled(false);
                    logWarn("No USB devices. Enable USB Debugging and reconnect.");
                } else {
                    found.forEach(cmbUsb::addItem);
                    btnEnableTcp.setEnabled(true);
                    logOk("USB devices found: " + String.join(", ", found));
                }
                btnRefreshUsb.setEnabled(true);
            });
        }, "usb-scan").start();
    }

    private void enableTcp() {
        String dev = (String) cmbUsb.getSelectedItem();
        if (dev == null || dev.startsWith("No USB")) {
            logWarn("No USB device selected.");
            return;
        }
        logCmd(ADB + " -s " + dev + " tcpip 5555");
        btnEnableTcp.setEnabled(false);
        new Thread(() -> {
            CmdResult r = exec(true, ADB, "-s", dev, "tcpip", "5555");
            SwingUtilities.invokeLater(() -> {
                btnEnableTcp.setEnabled(true);
                if (r.stdout.contains("restarting")) {
                    logOk("TCP/IP mode enabled on " + dev + " — you can now unplug USB.");
                } else {
                    logError("TCP/IP failed: " + (r.stdout.isEmpty() ? r.stderr : r.stdout));
                }
            });
        }, "tcp-enable").start();
    }

    private void connectDevice() {
        String ip = txIp.getText().trim();
        if (ip.isEmpty() || ip.equals("100.")) {
            logWarn("Enter a valid IP address.");
            return;
        }
        String target = ip + ":5555";
        logCmd(ADB + " connect " + target);
        btnConnect.setEnabled(false);
        new Thread(() -> {
            CmdResult r = exec(true, ADB, "connect", target);
            SwingUtilities.invokeLater(() -> {
                btnConnect.setEnabled(true);
                if (r.stdout.contains("connected") || r.stdout.contains("already connected")) {
                    logOk("ADB connected → " + target);
                    btnLaunch.setEnabled(true);
                    styleStatus(true);
                } else {
                    logError("Connection refused: " + r.stdout);
                    logWarn("Check IP, port 5555 open, and Tailscale/Wi-Fi active.");
                    styleStatus(false);
                }
            });
        }, "adb-connect").start();
    }

    private void disconnectAll() {
        logCmd(ADB + " disconnect");
        new Thread(() -> {
            CmdResult r = exec(true, ADB, "disconnect");
            SwingUtilities.invokeLater(() -> {
                logInfo("ADB: " + r.stdout);
                btnLaunch.setEnabled(false);
                styleStatus(false);
                lblBattery.setText("   Battery: —");
                lblBattery.setDot(TXT_MUT);
                lblTemp.setText("   Temp: —");
                lblTemp.setDot(TXT_MUT);
                lblScreen.setText("   Screen: —");
                lblScreen.setDot(TXT_MUT);
            });
        }, "adb-disconnect").start();
    }

    private void launchScrcpy() {
        String ip = txIp.getText().trim();
        if (ip.isEmpty() || ip.equals("100.")) {
            logWarn("No IP configured.");
            return;
        }
        String target = ip + ":5555";

        List<String> cmd = new ArrayList<>(List.of(SCRCPY, "-s", target));
        String br = Objects.toString(cmbBitrate.getSelectedItem(), "2M — Balanced").split(" ")[0];
        cmd.add("-b");
        cmd.add(br);
        String res = Objects.toString(cmbRes.getSelectedItem(), "720");
        if (!res.equalsIgnoreCase("Max")) {
            cmd.add("-m");
            cmd.add(res);
        }
        cmd.add("--max-fps");
        cmd.add(Objects.toString(cmbFps.getSelectedItem(), "30"));
        if (chkPresentation.isSelected()) {
            cmd.add("--window-borderless");
            cmd.add("--always-on-top");
        }
        if (chkNoAudio.isSelected()) {
            cmd.add("--no-audio");
        }
        if (chkMutePhone.isSelected()) {
            cmd.add("--no-audio-playback");
        }
        if (chkVerbose.isSelected()) {
            cmd.add("-V");
            cmd.add("verbose");
        }

        logCmd(SCRCPY + " " + String.join(" ", cmd.subList(1, cmd.size())));
        logInfo("Starting scrcpy session → " + target);
        btnLaunch.setEnabled(false);
        btnLaunch.setText("  ◉  SESSION ACTIVE…");

        new Thread(() -> {
            try {
                Process proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                procMgr.register("scrcpy-main", proc);
                new Thread(() -> {
                    try (BufferedReader rd = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                        String ln;
                        while ((ln = rd.readLine()) != null) {
                            String msg = ln;
                            if (msg.contains("ERROR")) {
                                logError("[scrcpy] " + msg);
                            } else if (msg.contains("WARN")) {
                                logWarn("[scrcpy] " + msg);
                            } else if (chkVerbose.isSelected()) {
                                logDebug("[scrcpy] " + msg);
                            }
                        }
                    } catch (IOException ignored) {
                    }
                }, "scrcpy-stdout").start();

                int exit = proc.waitFor();
                procMgr.stop("scrcpy-main", 2000);
                SwingUtilities.invokeLater(() -> {
                    if (exit == 0) {
                        logOk("scrcpy session ended normally (exit 0).");
                    } else {
                        logWarn("scrcpy exited with code " + exit + ".");
                    }
                    btnLaunch.setText("▶   LAUNCH SCRCPY STREAM");
                });

                if (chkAutoReconnect.isSelected() && exit != 0) {
                    logInfo("Auto-reconnect in 5 s...");
                    Thread.sleep(5000);
                    execSilent(ADB, "connect", target);
                    SwingUtilities.invokeLater(this::launchScrcpy);
                } else {
                    SwingUtilities.invokeLater(() -> btnLaunch.setEnabled(true));
                }
            } catch (Exception ex) {
                logError("scrcpy launch failed: " + ex.getMessage());
                SwingUtilities.invokeLater(() -> {
                    btnLaunch.setEnabled(true);
                    btnLaunch.setText("▶   LAUNCH SCRCPY STREAM");
                });
            }
        }, "scrcpy-session").start();
    }

    private void takeScreenshot() {
        String dev = activeDevice();
        if (dev == null) {
            logWarn("No active device for screenshot.");
            return;
        }
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        File dest = new File(saveFolder, "screen_" + ts + ".png");
        logCmd(ADB + " -s " + dev + " exec-out screencap -p → " + dest.getName());
        new Thread(() -> {
            try {
                Process p = new ProcessBuilder(ADB, "-s", dev, "exec-out", "screencap", "-p")
                        .redirectOutput(dest).start();
                procMgr.register("screenshot", p);
                int code = p.waitFor();
                procMgr.stop("screenshot", 1000);
                if (code == 0 && dest.length() > 0) {
                    logOk("Screenshot saved: " + dest.getName());
                } else {
                    logError("Screenshot failed (exit " + code + "). Screen may be locked.");
                }
            } catch (Exception ex) {
                logError("Screenshot error: " + ex.getMessage());
            }
        }, "screenshot").start();
    }

    private void toggleMic() {
        if (micActive.get()) {
            procMgr.stop("mic-record", 3000);
            micActive.set(false);
            SwingUtilities.invokeLater(() -> {
                btnRecordMic.setText("⏺  Record Mic");
                logOk("Mic recording stopped and saved.");
            });
            return;
        }
        String dev = activeDevice();
        if (dev == null) {
            logWarn("No active device for mic recording.");
            return;
        }
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        File dest = new File(REC_DIR, "mic_" + ts + ".m4a");
        logCmd(SCRCPY + " -s " + dev + " --no-video --no-window --audio-source=mic --record=" + dest.getName());
        micActive.set(true);
        SwingUtilities.invokeLater(() -> btnRecordMic.setText("⏹  Stop & Save"));
        logInfo("Mic recording started → " + dest.getName());
        new Thread(() -> {
            try {
                Process p = new ProcessBuilder(
                        SCRCPY, "-s", dev, "--no-video", "--no-window",
                        "--audio-source=mic", "--record=" + dest.getAbsolutePath())
                        .redirectErrorStream(true).start();
                procMgr.register("mic-record", p);
                new Thread(() -> {
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                        while (r.readLine() != null) {
                        }
                    } catch (IOException ignored) {
                    }
                }, "mic-drain").start();
                p.waitFor();
            } catch (Exception ex) {
                logError("Mic error: " + ex.getMessage());
            } finally {
                micActive.set(false);
                SwingUtilities.invokeLater(() -> btnRecordMic.setText("⏺  Record Mic"));
            }
        }, "mic-record").start();
    }

    private void snapCam(String facing) {
        String dev = activeDevice();
        if (dev == null) {
            logWarn("No active device for camera snap.");
            return;
        }
        btnSnapFront.setEnabled(false);
        btnSnapBack.setEnabled(false);
        logInfo("Preparing " + facing + " camera capture...");
        new Thread(() -> {
            try {
                logDebug("Releasing camera lock on device...");
                execSilent(ADB, "-s", dev, "shell", "killall", "app_process");
                Thread.sleep(600);
                String ts = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
                File dest = new File(saveFolder, "cam_" + facing + "_" + ts + ".mp4");
                logCmd(SCRCPY + " -s " + dev + " --video-source=camera --camera-facing=" + facing
                        + " --no-window --no-audio --frames-limit=1 --record=" + dest.getName());
                Process p = new ProcessBuilder(
                        SCRCPY, "-s", dev, "--video-source=camera",
                        "--camera-facing=" + facing, "--no-window", "--no-audio",
                        "--frames-limit=1", "--record=" + dest.getAbsolutePath())
                        .redirectErrorStream(true).start();
                procMgr.register("cam-" + facing, p);
                new Thread(() -> {
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                        while (r.readLine() != null) {
                        }
                    } catch (IOException ignored) {
                    }
                }, "cam-drain").start();
                boolean done = p.waitFor(14, TimeUnit.SECONDS);
                procMgr.stop("cam-" + facing, 1000);
                if (!done) {
                    logError("Camera timeout. Ensure screen is AWAKE and camera app is closed.");
                } else if (p.exitValue() == 0) {
                    logOk("Camera snapshot saved: " + dest.getName());
                } else {
                    logError("Camera failed (exit " + p.exitValue() + "). Screen on? Camera app closed?");
                }
            } catch (Exception ex) {
                logError("Camera error: " + ex.getMessage());
            } finally {
                SwingUtilities.invokeLater(() -> {
                    btnSnapFront.setEnabled(true);
                    btnSnapBack.setEnabled(true);
                });
            }
        }, "cam-snap-" + facing).start();
    }

    private void toggleVpnIcon() {
        String dev = activeDevice();
        if (dev == null) {
            logWarn("No device for VPN icon toggle.");
            return;
        }
        new Thread(() -> {
            if (chkHideVpn.isSelected()) {
                execSilent(ADB, "-s", dev, "shell", "settings", "put", "secure", "icon_blacklist", "vpn");
                logInfo("VPN icon hidden.");
            } else {
                execSilent(ADB, "-s", dev, "shell", "settings", "delete", "secure", "icon_blacklist");
                logInfo("VPN icon restored.");
            }
        }, "vpn-toggle").start();
    }

    private void chooseFolder() {
        JFileChooser fc = new JFileChooser(saveFolder);
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            saveFolder = fc.getSelectedFile().getAbsolutePath();
            txFolder.setText(saveFolder);
            logInfo("Save folder: " + saveFolder);
        }
    }

    // =========================================================================
    //  VITALS MONITOR
    // =========================================================================
    private ScheduledExecutorService vitSched;

    private void startVitalsMonitor() {
        if (vitSched != null && !vitSched.isShutdown()) {
            return;
        }
        vitSched = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "vitals");
            t.setDaemon(true);
            return t;
        });
        vitSched.scheduleAtFixedRate(() -> {
            String dev = activeDevice();
            if (dev == null) {
                return;
            }
            try {
                String dump = execSilent(ADB, "-s", dev, "shell", "dumpsys", "battery");
                String lvl = "—", tmp = "—";
                boolean charging = false;
                for (String ln : dump.split("\n")) {
                    if (ln.contains("level:")) {
                        lvl = ln.split(":")[1].trim() + "%";
                    }
                    if (ln.contains("temperature:")) {
                        tmp = (Integer.parseInt(ln.split(":")[1].trim()) / 10.0) + "°C";
                    }
                    if (ln.contains("status: 2")) {
                        charging = true;
                    }
                }
                String pw = execSilent(ADB, "-s", dev, "shell", "dumpsys", "power");
                boolean screenOn = pw.contains("mHoldingDisplaySuspendBlocker=true")
                        || pw.contains("Display Power: state=ON");
                String fL = lvl, fT = tmp;
                boolean fC = charging, fS = screenOn;
                SwingUtilities.invokeLater(() -> {
                    lblBattery.setText("   Battery: " + fL + (fC ? " ⚡" : ""));
                    lblBattery.setDot(fC ? GREEN : fL.equals("—") ? TXT_MUT : AMBER);
                    lblTemp.setText("   Temp: " + fT);
                    lblTemp.setDot(fT.equals("—") ? TXT_MUT : CYAN);
                    lblScreen.setText("   Screen: " + (fS ? "ON" : "OFF"));
                    lblScreen.setDot(fS ? GREEN : RED);
                });
            } catch (Exception ignored) {
            }
        }, 2, 7, TimeUnit.SECONDS);
    }

    // =========================================================================
    //  PROFILE MANAGEMENT
    // =========================================================================
    private void loadProfiles() {
        if (PROFS.exists())
            try (FileInputStream in = new FileInputStream(PROFS)) {
            profiles.load(in);
        } catch (Exception ex) {
            logError("Could not load profiles: " + ex.getMessage());
        }
    }

    private void saveProfilesToDisk() {
        try (FileOutputStream out = new FileOutputStream(PROFS)) {
            profiles.store(out, "ScrcpyHub");
        } catch (Exception ex) {
            logError("Save failed: " + ex.getMessage());
        }
    }

    private void populateProfiles() {
        cmbProfiles.removeAllItems();
        cmbProfiles.addItem("— Select —");
        profiles.stringPropertyNames().stream().sorted().forEach(cmbProfiles::addItem);
    }

    private void onProfileSelected() {
        String sel = (String) cmbProfiles.getSelectedItem();
        if (sel != null && !sel.startsWith("—")) {
            txIp.setText(profiles.getProperty(sel));
            logInfo("Profile loaded: " + sel + " → " + profiles.getProperty(sel));
        }
    }

    private void saveProfile() {
        String ip = txIp.getText().trim();
        if (ip.isEmpty() || ip.equals("100.")) {
            logWarn("Enter an IP to save.");
            return;
        }
        String name = JOptionPane.showInputDialog(this, "Profile name:", "Save Profile", JOptionPane.PLAIN_MESSAGE);
        if (name != null && !name.trim().isEmpty()) {
            profiles.setProperty(name.trim(), ip);
            saveProfilesToDisk();
            populateProfiles();
            logOk("Profile saved: \"" + name.trim() + "\" → " + ip);
        }
    }

    private void deleteProfile() {
        String sel = (String) cmbProfiles.getSelectedItem();
        if (sel == null || sel.startsWith("—")) {
            logWarn("No profile selected.");
            return;
        }
        profiles.remove(sel);
        saveProfilesToDisk();
        populateProfiles();
        logInfo("Profile deleted: " + sel);
    }

    // =========================================================================
    //  HELPERS
    // =========================================================================
    private String activeDevice() {
        String ip = txIp.getText().trim();
        if (!ip.isEmpty() && !ip.equals("100.") && btnLaunch.isEnabled()) {
            return ip + ":5555";
        }
        String usb = (String) cmbUsb.getSelectedItem();
        return (usb != null && !usb.startsWith("No USB") && !usb.startsWith("Scanning")) ? usb : null;
    }

    private void styleStatus(boolean connected) {
        lblConnStatus.setText(connected ? " ● CONNECTED    " : " ● DISCONNECTED ");
        lblConnStatus.setForeground(connected ? GREEN : RED);
        lblConnStatus.setBackground(connected ? new Color(52, 211, 153, 25) : new Color(248, 92, 92, 25));
        lblConnStatus.setOpaque(true);
        lblConnStatus.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(connected ? new Color(52, 211, 153, 80) : new Color(248, 92, 92, 80), 1, true),
                BorderFactory.createEmptyBorder(4, 10, 4, 10)));
    }

    // =========================================================================
    //  ENTRY POINT
    // =========================================================================
    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }

        UIManager.put("Panel.background", BG_BASE);
        UIManager.put("Window.background", BG_BASE);
        UIManager.put("Control.background", BG_SURFACE);

        UIManager.put("OptionPane.background", BG_SURFACE);
        UIManager.put("OptionPane.messageForeground", TXT_PRI);

        UIManager.put("TextField.background", BG_ELEVATED);
        UIManager.put("TextField.foreground", TXT_PRI);
        UIManager.put("TextField.caretForeground", CYAN);
        UIManager.put("TextField.selectionBackground", CYAN_DIM);
        UIManager.put("TextField.selectionForeground", Color.WHITE);
        UIManager.put("TextField.border", BorderFactory.createLineBorder(BG_BORDER));

        UIManager.put("ComboBox.background", BG_ELEVATED);
        UIManager.put("ComboBox.foreground", TXT_PRI);
        UIManager.put("ComboBox.selectionBackground", CYAN_DIM);
        UIManager.put("ComboBox.selectionForeground", Color.WHITE);
        UIManager.put("ComboBox.buttonBackground", BG_SURFACE);

        UIManager.put("ScrollBar.background", BG_BASE);
        UIManager.put("ScrollBar.thumb", BG_BORDER);

        SwingUtilities.invokeLater(() -> {
            ScrcpyRemoteHub hub = new ScrcpyRemoteHub();
            hub.setVisible(true);
            new Thread(() -> {
                boolean adbExists = new File(ADB).exists();
                boolean scrExists = new File(SCRCPY).exists();
                SwingUtilities.invokeLater(() -> {
                    hub.logInfo("ADB      : " + (adbExists ? "FOUND in tools/" : "NOT FOUND — place adb.exe in tools/"));
                    hub.logInfo("scrcpy   : " + (scrExists ? "FOUND in tools/" : "NOT FOUND — place scrcpy.exe in tools/"));
                });
            }, "startup-check").start();
        });
    }
}
