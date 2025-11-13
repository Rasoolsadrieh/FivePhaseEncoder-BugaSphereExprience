import javax.sound.sampled.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class BugaFivePhaseDemoV2 {

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

    /* ---------- UI panel (color + rotating pentagon + labels) ---------- */
    static class Panel extends JPanel {
        Phase cur = PHASES[0];
        double rotationDeg = 0; // rotates 72°/phase

        void setPhase(Phase p){
            cur = p;
            rotationDeg += 72.0;
            repaint();
        }

        @Override public Dimension getPreferredSize(){ return new Dimension(1000, 640); }

        @Override protected void paintComponent(Graphics g){
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // Background = phase color
            g2.setColor(cur.color);
            g2.fillRect(0,0,getWidth(),getHeight());

            // Rotating pentagon outline (soft translucent)
            int cx = getWidth()/2, cy = getHeight()/2;
            int radius = Math.min(getWidth(), getHeight())/4;
            Polygon poly = new Polygon();
            for(int i=0;i<5;i++){
                double ang = Math.toRadians(rotationDeg + i*72 - 90);
                int x = cx + (int)(radius * Math.cos(ang));
                int y = cy + (int)(radius * Math.sin(ang));
                poly.addPoint(x,y);
            }
            g2.setStroke(new BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(new Color(0,0,0,60));
            g2.drawPolygon(poly);

            // Labels
            Color text = contrast(cur.color);
            g2.setColor(text);

            String title = "Five-Phase Encoder";
            g2.setFont(getFont().deriveFont(Font.BOLD, Math.max(26f, getWidth()*0.03f)));
            int tw = g2.getFontMetrics().stringWidth(title);
            g2.drawString(title, (getWidth()-tw)/2, (int)(getHeight()*0.15));

            String center = cur.name + "  •  " + (int)cur.hz + " Hz";
            g2.setFont(getFont().deriveFont(Font.BOLD, Math.max(56f, getWidth()*0.075f)));
            int cw = g2.getFontMetrics().stringWidth(center);
            g2.drawString(center, (getWidth()-cw)/2, (int)(getHeight()*0.54));

            String hint = "SPACE = Pause/Resume   ←/→ = Prev/Next   ESC = Quit";
            g2.setFont(getFont().deriveFont(Font.PLAIN, Math.max(16f, getWidth()*0.02f)));
            int hw = g2.getFontMetrics().stringWidth(hint);
            g2.drawString(hint, (getWidth()-hw)/2, (int)(getHeight()*0.90));

            g2.dispose();
        }

        static Color contrast(Color bg){
            double lum = (0.299*bg.getRed()+0.587*bg.getGreen()+0.114*bg.getBlue())/255.0;
            return lum > 0.6 ? Color.BLACK : Color.WHITE;
        }
    }

    /* ---------- Audio: stereo sine with envelope, pause/interrupt support ---------- */
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

        void close() {
            line.drain(); line.stop(); line.close();
        }
    }

    /* ---------- App entry ---------- */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame f = new JFrame("Five-Phase Encoder — V2");
            Panel panel = new Panel();
            f.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
            f.setContentPane(panel);
            f.pack();
            f.setLocationRelativeTo(null);
            f.setVisible(true);

            AtomicBoolean paused = new AtomicBoolean(false);
            AtomicBoolean interrupt = new AtomicBoolean(false);

            // Keyboard controls
            f.addKeyListener(new KeyAdapter() {
                @Override public void keyPressed(KeyEvent e) {
                    switch (e.getKeyCode()) {
                        case KeyEvent.VK_ESCAPE: f.dispose(); break;
                        case KeyEvent.VK_SPACE:  paused.set(!paused.get()); break;
                        case KeyEvent.VK_RIGHT:  interrupt.set(true); break; // next
                        case KeyEvent.VK_LEFT:   interrupt.set(true); break; // prev (same flag)
                    }
                }
            });

            // Run loop on background thread
            Thread loop = new Thread(() -> runEncoder(f, panel, paused, interrupt));
            loop.setDaemon(true);
            loop.start();
        });
    }

    private static void runEncoder(JFrame f, Panel panel,
                                   AtomicBoolean paused, AtomicBoolean interrupt) {
        TonePlayer player = null;
        try {
            player = new TonePlayer();
            int idx = 0;
            boolean panLeft = true;

            while (f.isDisplayable()) {
                int cur = idx;
                SwingUtilities.invokeAndWait(() -> panel.setPhase(PHASES[cur]));
                interrupt.set(false);
                player.playPhase(PHASES[cur].hz, PHASES[cur].ms, panLeft, paused, interrupt);

                // If interrupted, skip immediately to next phase
                idx = (idx + 1) % PHASES.length;
                panLeft = !panLeft;
            }
        } catch (Exception e) {
            e.printStackTrace();
            SwingUtilities.invokeLater(() ->
                    JOptionPane.showMessageDialog(f, "Error: " + e.getMessage(),
                            "Encoder Error", JOptionPane.ERROR_MESSAGE)
            );
        } finally {
            if (player != null) player.close();
        }
    }
}
