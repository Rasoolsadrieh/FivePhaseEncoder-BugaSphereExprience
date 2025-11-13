import javax.sound.sampled.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class BugaFivePhaseDemoV6 {

    /* ---------- Phase model ---------- */
    static class Phase {
        final String name; final Color color; final double hz; final int ms;
        Phase(String n, Color c, double f, int d){ name=n; color=c; hz=f; ms=d; }
    }
    static final Phase[] PHASES = {
            new Phase("Origin",  new Color(0xFFFFFF), 440.0, 2000),
            new Phase("Growth",  new Color(0x00FF66), 554.0, 2000),
            new Phase("Peak",    new Color(0x0066FF), 659.0, 2000),
            new Phase("Decline", new Color(0xFF3333), 587.0, 2000),
            new Phase("Renewal", new Color(0xCC33FF), 702.0, 2000)
    };

    enum BreathMode { INHALE_6, INHALE_8 }

    /* ---------- UI panel: background + pentagon; optional overlays ---------- */
    static class Panel extends JPanel {
        Phase cur = PHASES[0];
        double rotationDeg = 0;     // current angle
        double perFrameDeg = 0;     // increment per frame
        boolean showHud = true;     // when false -> show ONLY colors + pentagon
        String breathWord = "INHALE";
        BreathMode mode = BreathMode.INHALE_6;

        void startPhaseAnimation(int phaseMillis, int fps){
            int frames = Math.max(1, (phaseMillis * fps) / 1000);
            double targetAddDeg = 72.0; // one vertex step per phase
            perFrameDeg = targetAddDeg / frames;
        }

        final Timer anim = new Timer(16, e -> { rotationDeg += perFrameDeg; repaint(); });

        void setPhase(Phase p, int phaseMillis){
            cur = p;
            startPhaseAnimation(phaseMillis, 60);
            if (!anim.isRunning()) anim.start();
        }
        void setBreathWord(String w){ breathWord = w; if (showHud) repaint(); }
        void setBreathMode(BreathMode m){ mode = m; if (showHud) repaint(); }

        @Override public Dimension getPreferredSize(){ return new Dimension(1000, 640); }

        @Override protected void paintComponent(Graphics g){
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // Background = phase color
            g2.setColor(cur.color);
            g2.fillRect(0,0,getWidth(),getHeight());

            // Rotating pentagon (visible always)
            int cx = getWidth()/2, cy = getHeight()/2;
            int radius = Math.min(getWidth(), getHeight())/4;
            Polygon poly = new Polygon();
            for(int i=0;i<5;i++){
                double ang = Math.toRadians(rotationDeg + i*72 - 90);
                int x = cx + (int)(radius * Math.cos(ang));
                int y = cy + (int)(radius * Math.sin(ang));
                poly.addPoint(x,y);
            }
            g2.setColor(new Color(0,0,0,45));
            g2.fillPolygon(poly);
            g2.setStroke(new BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(new Color(0,0,0,90));
            g2.drawPolygon(poly);

            // If HUD is ON, draw words & labels; if OFF, show only color + pentagon
            if (showHud) {
                // Breath word
                g2.setFont(getFont().deriveFont(Font.BOLD, Math.max(64f, getWidth()*0.09f)));
                Color overlay = (contrast(cur.color) == Color.BLACK)
                        ? new Color(0,0,0,120) : new Color(255,255,255,160);
                g2.setColor(overlay);
                int bw = g2.getFontMetrics().stringWidth(breathWord);
                g2.drawString(breathWord.toUpperCase(), (getWidth()-bw)/2, (int)(getHeight()*0.36));

                // Title + phase label + help line
                Color text = contrast(cur.color);
                g2.setColor(text);

                String title = "Five-Phase Encoder";
                g2.setFont(getFont().deriveFont(Font.BOLD, Math.max(26f, getWidth()*0.03f)));
                int tw = g2.getFontMetrics().stringWidth(title);
                g2.drawString(title, (getWidth()-tw)/2, (int)(getHeight()*0.12));

                String center = cur.name + "  •  " + (int)cur.hz + " Hz";
                g2.setFont(getFont().deriveFont(Font.BOLD, Math.max(56f, getWidth()*0.075f)));
                int cw = g2.getFontMetrics().stringWidth(center);
                g2.drawString(center, (getWidth()-cw)/2, (int)(getHeight()*0.60));

                String hint = "Buttons/HUD visible • H = Hide HUD • F11 = Fullscreen • SPACE = Pause • ←/→ = Prev/Next • ESC = Quit";
                g2.setFont(getFont().deriveFont(Font.PLAIN, Math.max(15f, getWidth()*0.018f)));
                int hw = g2.getFontMetrics().stringWidth(hint);
                g2.drawString(hint, (getWidth()-hw)/2, (int)(getHeight()*0.92));
            }

            g2.dispose();
        }

        static Color contrast(Color bg){
            double lum = (0.299*bg.getRed()+0.587*bg.getGreen()+0.114*bg.getBlue())/255.0;
            return lum > 0.6 ? Color.BLACK : Color.WHITE;
        }
    }

    /* ---------- Audio ---------- */
    static class TonePlayer {
        final float SR = 44100f;
        final AudioFormat fmt = new AudioFormat(SR, 16, 2, true, false); // stereo
        final SourceDataLine line;

        TonePlayer() throws LineUnavailableException {
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);
            line = (SourceDataLine) AudioSystem.getLine(info);
            line.open(fmt);
            line.start();
        }

        void playPhase(double hz, int ms, boolean panLeft,
                       AtomicBoolean paused, AtomicBoolean interrupt) {

            final int totalSamples = (int)((ms/1000.0)*SR);
            final int attack = Math.min((int)(0.10*SR), totalSamples/3);
            final int release = Math.min((int)(0.30*SR), totalSamples/3);

            final int chunk = 512;
            final double step = 2*Math.PI*hz/SR;
            double phase = 0;

            int i = 0;
            while (i < totalSamples) {
                if (interrupt.get()) break;
                if (paused.get()) {
                    try { Thread.sleep(20); } catch (InterruptedException ignored) {}
                    continue;
                }
                int remain = totalSamples - i;
                int thisChunk = Math.min(chunk, remain);
                byte[] buf = new byte[thisChunk * 4];

                for (int s = 0; s < thisChunk; s++, i++) {
                    double gain;
                    if (i < attack) gain = i/(double)attack;
                    else if (i > totalSamples - release) {
                        int rel = i - (totalSamples - release);
                        gain = 1.0 - rel/(double)release;
                    } else gain = 1.0;

                    double sample = Math.sin(phase)*0.45*gain;
                    phase += step;

                    double leftGain  = panLeft ? 1.0 : 0.5;
                    double rightGain = panLeft ? 0.5 : 1.0;

                    short L = (short)(sample*leftGain *Short.MAX_VALUE);
                    short R = (short)(sample*rightGain*Short.MAX_VALUE);

                    int idx = s*4;
                    buf[idx]=(byte)(L&0xFF); buf[idx+1]=(byte)((L>>8)&0xFF);
                    buf[idx+2]=(byte)(R&0xFF); buf[idx+3]=(byte)((R>>8)&0xFF);
                }
                line.write(buf,0,buf.length);
            }
        }

        void close() { line.drain(); line.stop(); line.close(); }
    }

    /* ---------- Fullscreen helper ---------- */
    static class Fullscreen {
        private final JFrame frame;
        private boolean isFullscreen = false;
        private Rectangle windowedBounds;
        private boolean wasDecorated;

        Fullscreen(JFrame f){ this.frame = f; }

        void toggle(){
            GraphicsDevice gd = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
            if (!isFullscreen) {
                windowedBounds = frame.getBounds();
                wasDecorated = frame.isUndecorated();
                frame.dispose();
                frame.setUndecorated(true);
                try { gd.setFullScreenWindow(frame); } catch (Exception ignored) {}
                frame.setVisible(true);
                isFullscreen = true;
            } else {
                GraphicsDevice gd2 = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
                gd2.setFullScreenWindow(null);
                frame.dispose();
                frame.setUndecorated(wasDecorated);
                frame.setBounds(windowedBounds != null ? windowedBounds : new Rectangle(100,100,1000,640));
                frame.setVisible(true);
                isFullscreen = false;
            }
        }
    }

    /* ---------- App entry ---------- */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame f = new JFrame("Five-Phase Encoder — V6 (HUD hide shows only visuals)");
            f.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
            f.setLayout(new BorderLayout());

            // Center: animated panel
            Panel panel = new Panel();
            f.add(panel, BorderLayout.CENTER);

            // Top: breathing mode controls (hidden when HUD is off)
            JPanel controls = new JPanel() {
                @Override protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setColor(new Color(0,0,0,80));
                    g2.fillRoundRect(6,6,getWidth()-12,getHeight()-12,16,16);
                    g2.dispose();
                    super.paintComponent(g);
                }
            };
            controls.setOpaque(false);
            controls.setLayout(new FlowLayout(FlowLayout.CENTER, 16, 8));

            JRadioButton btn6 = new JRadioButton("INHALE 6s / EXHALE 4s");
            JRadioButton btn8 = new JRadioButton("INHALE 8s / EXHALE 2s");
            ButtonGroup group = new ButtonGroup();
            group.add(btn6); group.add(btn8);
            btn6.setSelected(true);
            btn6.setFocusable(false); btn8.setFocusable(false);
            btn6.setForeground(Color.WHITE); btn8.setForeground(Color.WHITE);
            btn6.setOpaque(false); btn8.setOpaque(false);
            controls.add(btn6); controls.add(btn8);
            f.add(controls, BorderLayout.NORTH);

            Fullscreen fs = new Fullscreen(f);

            AtomicBoolean paused = new AtomicBoolean(false);
            AtomicBoolean interrupt = new AtomicBoolean(false);
            final int[] idx = {0};
            final boolean[] panLeft = {true};
            final BreathMode[] mode = {BreathMode.INHALE_6};

            // Buttons: update breathing mode
            btn6.addActionListener(e -> mode[0] = BreathMode.INHALE_6);
            btn8.addActionListener(e -> mode[0] = BreathMode.INHALE_8);

            // Keyboard controls
            f.addKeyListener(new KeyAdapter() {
                @Override public void keyPressed(KeyEvent e) {
                    switch (e.getKeyCode()) {
                        case KeyEvent.VK_ESCAPE: f.dispose(); break;
                        case KeyEvent.VK_SPACE:  paused.set(!paused.get()); break;
                        case KeyEvent.VK_RIGHT:  interrupt.set(true); idx[0] = (idx[0]+1)%PHASES.length; break;
                        case KeyEvent.VK_LEFT:   interrupt.set(true); idx[0] = (idx[0]+PHASES.length-1)%PHASES.length; break;
                        case KeyEvent.VK_F11:    fs.toggle(); break;
                        case KeyEvent.VK_H:
                            panel.showHud = !panel.showHud;
                            controls.setVisible(panel.showHud); // hide buttons too
                            f.revalidate();
                            panel.repaint();
                            break;
                        case KeyEvent.VK_6:      btn6.doClick(); break; // keep hotkeys
                        case KeyEvent.VK_8:      btn8.doClick(); break;
                    }
                }
            });

            f.pack();
            f.setLocationRelativeTo(null);
            f.setVisible(true);

            // Loop
            Thread loop = new Thread(() -> {
                TonePlayer player = null;
                try {
                    player = new TonePlayer();
                    while (f.isDisplayable()) {
                        int cur = idx[0];

                        boolean inhaleNow = isInhale(mode[0], cur);
                        SwingUtilities.invokeAndWait(() -> {
                            panel.setBreathMode(mode[0]);
                            panel.setBreathWord(inhaleNow ? "INHALE" : "EXHALE");
                            panel.setPhase(PHASES[cur], PHASES[cur].ms);
                        });

                        interrupt.set(false);
                        player.playPhase(PHASES[cur].hz, PHASES[cur].ms, panLeft[0], paused, interrupt);

                        if (!interrupt.get()) {
                            idx[0] = (idx[0]+1) % PHASES.length;
                        }
                        panLeft[0] = !panLeft[0];
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                    SwingUtilities.invokeLater(() ->
                            JOptionPane.showMessageDialog(f, "Error: " + ex.getMessage(),
                                    "Encoder Error", JOptionPane.ERROR_MESSAGE)
                    );
                } finally {
                    if (player != null) player.close();
                }
            });
            loop.setDaemon(true);
            loop.start();
        });
    }

    // INHALE phases for selected mode
    private static boolean isInhale(BreathMode mode, int phaseIndex){
        switch (mode) {
            case INHALE_6: return (phaseIndex == 0 || phaseIndex == 1 || phaseIndex == 2);             // 6s in, 4s out
            case INHALE_8: return (phaseIndex == 0 || phaseIndex == 1 || phaseIndex == 2 || phaseIndex == 3); // 8s in, 2s out
            default:       return true;
        }
    }
}
