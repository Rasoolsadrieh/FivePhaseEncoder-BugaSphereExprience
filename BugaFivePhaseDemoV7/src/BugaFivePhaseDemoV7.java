// BugaFivePhaseDemoV7.java — v7.12
// Changes in v7.12:
//  • Removed the 7.5 / 2.5 breathing option (only 6/4 and 8/2 remain).
//  • Use ONE accent color (same as Rotation label) for all main labels: Breath, Transition, Speed, Rotation.
//  • Everything else unchanged from v7.11 (gray outline & needle; glows; modes incl. 10s, 15s, 60s, 120s).
//
// Keys:
//   Breath: [1] 6–4   [2] 8–2
//   Transition: [3] Hard   [4] Soft
//   Speed: [5] Fast 10s   [L] 15s   [6] Meditative 60s   [9] Deep 120s
//   Rotation: [7] Continuous   [8] Kinetic 72°   [0] No motion
//   Misc: [H] HUD   [SPACE] Pause   [F11]/Alt+Enter Fullscreen   [Esc] Exit

import javax.sound.sampled.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.geom.Point2D;
import java.util.concurrent.atomic.AtomicBoolean;

public class BugaFivePhaseDemoV7 {

    /* ---------- Phase model ---------- */
    static class Phase {
        final String name; final Color color; final double hz;
        Phase(String n, Color c, double f){ name=n; color=c; hz=f; }
    }
    static final Phase[] PHASES = {
            new Phase("Origin",  new Color(0xFFFFFF), 440.0),
            new Phase("Growth",  new Color(0x00FFFF), 494.0),
            new Phase("Peak",    new Color(0x00FF00), 556.0),
            new Phase("Decline", new Color(0xFFBF00), 624.0),
            new Phase("Renewal", new Color(0xFF00FF), 702.0)
    };

    enum BreathMode {
        INHALE_6,  // 6/4 per 10s block
        INHALE_8   // 8/2 per 10s block
    }
    enum TransitionMode { HARD_CUT, SOFT }
    enum SpeedMode {
        FAST_10S,       // 2 s per phase
        BALANCED_15S,   // 3 s per phase
        MEDITATIVE_60S, // 12 s per phase
        DEEP_120S       // 24 s per phase
    }
    enum RotationMode { CONTINUOUS, KINETIC_STEP, NO_MOTION }

    /* ---------- Visual panel ---------- */
    static class Panel extends JPanel {
        Phase cur = PHASES[0];
        int   phaseIndex = 0;
        boolean showHud = true;

        BreathMode   breathMode   = BreathMode.INHALE_6;
        TransitionMode transition = TransitionMode.SOFT;
        SpeedMode    speedMode    = SpeedMode.FAST_10S;
        RotationMode rotationMode = RotationMode.CONTINUOUS;

        // Crossfade (background only)
        private Color fadeFrom = PHASES[0].color, fadeTo = PHASES[0].color;
        private long  fadeStartNanos = 0;
        private int   fadeMs = 500;

        // Phase timing
        long phaseStartNanos = 0;
        int  phaseMsCurrent  = perPhaseMs();

        // Pause-aware clocks
        private final long appStartNanos = System.nanoTime();
        private long pausedAccumNanos = 0L;
        private long pausedAtNanos = 0L;
        private volatile boolean pausedVisual = false;

        // Rotation state
        private double currentAngleDeg = 0.0;
        private double rotStartDeg = 0.0;
        private double rotTargetDeg = 0.0;
        private double rotationDeg = 0.0;
        private static final long ROTATE_ANIM_MS = 800;

        // Breath metronome (10s cycles)
        private static final int BREATH_BLOCK_MS = 10_000;
        private long breathStartNanos = System.nanoTime();

        final AtomicBoolean resetRequested = new AtomicBoolean(false);

        // Fixed gray for outline & needle
        private static final Color PENTA_EDGE   = new Color(0x5A5A5A);
        private static final Color NEEDLE_COLOR = new Color(0x5A5A5A);

        Panel() {
            setBackground(new Color(20,20,20));
            setDoubleBuffered(true);
        }

        int perPhaseMs() {
            switch (speedMode) {
                case BALANCED_15S:   return 3000;
                case MEDITATIVE_60S: return 12000;
                case DEEP_120S:      return 24000;
                default:             return 2000;   // FAST_10S
            }
        }
        long loopPeriodNanos() { return (long) perPhaseMs() * 5L * 1_000_000L; }

        void beginColorFade(Color from, Color to){
            fadeFrom = from; fadeTo = to; fadeStartNanos = System.nanoTime();
        }

        final Timer anim = new Timer(16, e -> repaint());

        void setPausedVisual(boolean paused){
            if (this.pausedVisual == paused) return;
            this.pausedVisual = paused;
            if (paused) {
                pausedAtNanos = System.nanoTime();
                if (anim.isRunning()) anim.stop();
            } else {
                long now = System.nanoTime();
                long delta = now - pausedAtNanos;
                pausedAccumNanos += delta;
                if (fadeStartNanos != 0)  fadeStartNanos  += delta;
                if (phaseStartNanos != 0) phaseStartNanos += delta;
                breathStartNanos += delta;
                if (!anim.isRunning()) anim.start();
            }
            repaint();
        }

        void resetToTop() {
            phaseMsCurrent  = perPhaseMs();
            phaseIndex = 0;
            cur = PHASES[0];
            fadeFrom = fadeTo = cur.color;
            fadeStartNanos = 0;

            currentAngleDeg = 0.0;
            rotStartDeg = 0.0;
            rotTargetDeg = (rotationMode == RotationMode.NO_MOTION) ? 0.0 : 72.0;
            rotationDeg = 0.0;

            phaseStartNanos = pausedVisual ? pausedAtNanos : System.nanoTime();
            breathStartNanos = pausedVisual ? pausedAtNanos : System.nanoTime();

            if (!anim.isRunning() && !pausedVisual) anim.start();
        }

        void setPhaseAtAudioStart(Phase p, Color prevColor, int idx){
            this.phaseMsCurrent = perPhaseMs();

            if (transition == TransitionMode.SOFT) beginColorFade(prevColor, p.color);
            else { fadeFrom = p.color; fadeTo = p.color; fadeStartNanos = 0; }

            cur = p;
            phaseIndex = idx;

            if (rotationMode == RotationMode.NO_MOTION) {
                rotStartDeg  = currentAngleDeg % 360.0;
                rotTargetDeg = currentAngleDeg % 360.0;
            } else {
                rotStartDeg  = currentAngleDeg % 360.0;
                rotTargetDeg = (currentAngleDeg + 72.0) % 360.0;
            }

            phaseStartNanos = pausedVisual ? pausedAtNanos : System.nanoTime();
            if (!pausedVisual && !anim.isRunning()) anim.start();
        }

        void setBreathMode(BreathMode m){ breathMode = m; resetRequested.set(true); }
        void setTransitionMode(TransitionMode tm){ transition = tm; resetRequested.set(true); }
        void setSpeedMode(SpeedMode sm){ speedMode = sm; resetRequested.set(true); }
        void setRotationMode(RotationMode rm){ rotationMode = rm; resetRequested.set(true); }

        long overallMillis(){
            long now = pausedVisual ? pausedAtNanos : System.nanoTime();
            long eff = now - appStartNanos - pausedAccumNanos;
            return Math.max(0L, eff / 1_000_000L);
        }
        long loopMillis(){
            long now = pausedVisual ? pausedAtNanos : System.nanoTime();
            long eff = now - appStartNanos - pausedAccumNanos;
            long perLoop = loopPeriodNanos();
            long mod = ((eff % perLoop) + perLoop) % perLoop;
            return mod / 1_000_000L;
        }
        static String fmtMillis(long ms){
            long s = ms / 1000, m = s / 60, h = m / 60;
            s %= 60; m %= 60;
            return String.format("%02d:%02d:%02d", h, m, s);
        }
        static String fmtSec1(long ms){ return String.format("%.1f", ms/1000.0); }

        static double smooth(double t){ t=Math.max(0,Math.min(1,t)); return t*t*(3-2*t); }
        static double lerpDeg(double a, double b, double t){
            double da = ((b - a + 540) % 360) - 180;
            return (a + da * t + 360) % 360;
        }

        @Override public Dimension getPreferredSize(){ return new Dimension(1000,640); }

        @Override protected void paintComponent(Graphics g){
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            Color bg = cur.color;
            if (transition == TransitionMode.SOFT && fadeStartNanos != 0) {
                long now = pausedVisual ? pausedAtNanos : System.nanoTime();
                double t = (now - fadeStartNanos) / 1e6;
                bg = blend(fadeFrom, fadeTo, smooth(t / fadeMs));
            }

            g2.setComposite(AlphaComposite.SrcOver);
            g2.setColor(bg);
            g2.fillRect(0,0,getWidth(),getHeight());

            if (rotationMode == RotationMode.NO_MOTION) {
                rotationDeg = currentAngleDeg % 360.0;
            } else if (phaseStartNanos == 0) {
                rotationDeg = currentAngleDeg % 360.0;
            } else if (rotationMode == RotationMode.KINETIC_STEP) {
                long nowN = pausedVisual ? pausedAtNanos : System.nanoTime();
                double ms = (nowN - phaseStartNanos) / 1e6;
                double u = smooth(Math.min(1.0, ms / ROTATE_ANIM_MS));
                rotationDeg = lerpDeg(rotStartDeg, rotTargetDeg, u);
            } else {
                long nowN = pausedVisual ? pausedAtNanos : System.nanoTime();
                double ms = (nowN - phaseStartNanos) / 1e6;
                double u = Math.max(0, Math.min(1, ms / Math.max(1, phaseMsCurrent)));
                rotationDeg = lerpDeg(rotStartDeg, rotTargetDeg, u);
            }

            int cx = getWidth()/2, cy = getHeight()/2;
            int radius = Math.min(getWidth(),getHeight())/4;
            Polygon poly = new Polygon();
            double[] xs = new double[5], ys = new double[5];
            for(int i=0;i<5;i++){
                double ang=Math.toRadians(rotationDeg+i*72-90);
                double x=cx+radius*Math.cos(ang);
                double y=cy+radius*Math.sin(ang);
                xs[i]=x; ys[i]=y;
                poly.addPoint((int)Math.round(x),(int)Math.round(y));
            }

            // Drop shadow
            Graphics2D s = (Graphics2D)g2.create();
            s.translate(4,6);
            s.setColor(new Color(0,0,0,70));
            s.fillPolygon(poly);
            s.dispose();

            // Phase-colored 3D fill
            Color base   = cur.color;
            Color lighter= blend(base, Color.WHITE, 0.55);
            Color darker = blend(base, Color.BLACK, 0.35);
            float r=(float)(radius*1.25);
            Point2D center=new Point2D.Float((float)(cx-radius*0.35),(float)(cy-radius*0.35));
            float[] dist={0f,0.6f,1f};
            Color[] cols={lighter,base,darker};
            RadialGradientPaint rgp=new RadialGradientPaint(center,r,dist,cols);
            g2.setPaint(rgp);
            g2.fillPolygon(poly);

            // AO + bright corner glows
            drawVertexAO(g2, xs, ys, cx, cy);
            drawCornerGlows(g2, xs, ys, cur.color);

            // Fixed gray outline
            g2.setStroke(new BasicStroke(6f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
            g2.setColor(PENTA_EDGE);
            g2.drawPolygon(poly);

            // Needle (hidden in NO_MOTION)
            if (rotationMode != RotationMode.NO_MOTION) {
                drawNeedle(g2, cx, cy, radius);
            }

            // Breath word + countdown (HUD only)
            int inhaleMs, exhaleMs;
            switch (breathMode) {
                case INHALE_8: inhaleMs = 8000; exhaleMs = 2000; break;
                default:       inhaleMs = 6000; exhaleMs = 4000; break; // INHALE_6
            }
            long nowN = pausedVisual ? pausedAtNanos : System.nanoTime();
            long breathMs = Math.max(0L, (nowN - breathStartNanos) / 1_000_000L);
            long pos = breathMs % BREATH_BLOCK_MS;
            boolean inInhale = pos < inhaleMs;

            if (showHud) {
                String breathWordNow = inInhale ? "INHALE" : "EXHALE";
                g2.setFont(getFont().deriveFont(Font.BOLD, Math.max(64f,getWidth()*0.09f)));
                Color overlay=(contrast(bg)==Color.BLACK)?new Color(0,0,0,120):new Color(255,255,255,160);
                g2.setColor(overlay);
                int bw=g2.getFontMetrics().stringWidth(breathWordNow);
                g2.drawString(breathWordNow,(getWidth()-bw)/2,(int)(getHeight()*0.36));

                int segmentMs = inInhale ? inhaleMs : exhaleMs;
                int segPosMs  = (int)(inInhale ? pos : (pos - inhaleMs));
                int remaining = (int)Math.ceil((segmentMs - segPosMs) / 1000.0);
                drawBreathCountdownChip(g2, cx, cy, radius, remaining, inInhale, bg);
            }

            // HUD footer
            if(showHud){
                g2.setColor(contrast(bg));
                g2.setFont(getFont().deriveFont(Font.BOLD, 24f));
                g2.drawString("Five-Phase Encoder", 24, 40);
                g2.setFont(getFont().deriveFont(Font.PLAIN, 18f));
                g2.drawString("Phase Tone: " + (int)cur.hz + " Hz  •  " + (perPhaseMs()/1000.0) + " s", 24, 68);

                g2.setColor(contrast(bg));
                g2.setFont(getFont().deriveFont(Font.PLAIN, 14f));
                String loopCap = (speedMode==SpeedMode.FAST_10S) ? " / 10.0s"
                        : (speedMode==SpeedMode.BALANCED_15S) ? " / 15.0s"
                        : (speedMode==SpeedMode.MEDITATIVE_60S) ? " / 60.0s"
                        : " / 120.0s";
                String loopStr = "Loop: " + fmtSec1(loopMillis()) + loopCap;
                String totalStr = "Total: " + fmtMillis(overallMillis());
                String breathDur = (breathMode==BreathMode.INHALE_8) ? "8/2" : "6/4";
                String help = "[F11 / Alt+Enter] Full Screen   [SPACE] Pause   [H] HUD   [1] 6–4   [2] 8–2   [3] Hard   [4] Soft   [5] 10s   [L] 15s   [6] 60s   [9] 120s   [7] Continuous   [8] Kinetic   [0] No motion   [Esc] Exit";
                String footer = loopStr + "    " + totalStr + "    " + breathDur + "    " + help;

                int w = g2.getFontMetrics().stringWidth(footer);
                g2.drawString(footer, Math.max(24, getWidth()/2 - w/2), getHeight()-24);

                // Right chips
                String modeLbl = "Breath: " + breathDur;
                String transLbl = "Transition: " + (transition==TransitionMode.HARD_CUT ? "Hard" : "Soft");
                String speedLbl = "Speed: " + (
                        speedMode==SpeedMode.FAST_10S ? "Fast 10s"
                                : speedMode==SpeedMode.BALANCED_15S ? "Balanced 15s"
                                : speedMode==SpeedMode.MEDITATIVE_60S ? "Meditative 60s"
                                : "Deep 120s");
                String rotLbl   = "Rotation: " + (
                        rotationMode==RotationMode.NO_MOTION ? "No motion" :
                                rotationMode==RotationMode.CONTINUOUS ? "Continuous" : "Kinetic 72°"
                );
                g2.setFont(getFont().deriveFont(Font.PLAIN, 14f));
                int xRight = getWidth() - 24;

                int w1 = g2.getFontMetrics().stringWidth(modeLbl) + 20;
                int w2 = g2.getFontMetrics().stringWidth(transLbl) + 20;
                int w3 = g2.getFontMetrics().stringWidth(speedLbl) + 20;
                int w4 = g2.getFontMetrics().stringWidth(rotLbl) + 20;

                g2.setColor(new Color(0,0,0,80));
                g2.fillRoundRect(xRight - w1, 16, w1, 24, 16, 16);
                g2.fillRoundRect(xRight - w2, 44, w2, 24, 16, 16);
                g2.fillRoundRect(xRight - w3, 72, w3, 24, 16, 16);
                g2.fillRoundRect(xRight - w4, 100, w4, 24, 16, 16);

                g2.setColor(contrast(bg));
                g2.drawString(modeLbl,  xRight - w1 + 10, 33);
                g2.drawString(transLbl, xRight - w2 + 10, 61);
                g2.drawString(speedLbl, xRight - w3 + 10, 89);
                g2.drawString(rotLbl,   xRight - w4 + 10, 117);
            }

            g2.dispose();
        }

        private void drawBreathCountdownChip(Graphics2D g2, int cx, int cy, int radius,
                                             int remaining, boolean inInhale, Color bg){
            int boxH = 64;
            int boxW = 140;
            int x = cx + radius + 40;
            int y = cy - boxH/2;

            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(0,0,0,90));
            g2.fillRoundRect(x, y, boxW, boxH, 16, 16);

            Color fg = contrast(bg);
            g2.setColor(fg);

            g2.setFont(getFont().deriveFont(Font.BOLD, 14f));
            String lbl = inInhale ? "IN" : "EX";
            g2.drawString(lbl, x + 16, y + 20);

            g2.setFont(getFont().deriveFont(Font.BOLD, 36f));
            String txt = String.valueOf(Math.max(0, remaining));
            FontMetrics fm = g2.getFontMetrics();
            int tw = fm.stringWidth(txt);
            g2.drawString(txt, x + boxW - 16 - tw, y + 48);
        }

        private void drawVertexAO(Graphics2D g2, double[] xs, double[] ys, int cx, int cy){
            for (int i = 0; i < 5; i++) {
                double vx = xs[i], vy = ys[i];
                double ox = cx + (vx - cx) * 0.92;
                double oy = cy + (vy - cy) * 0.92;
                float rr = 18f;
                Point2D p = new Point2D.Float((float)ox, (float)oy);
                float[] dist = {0f, 1f};
                Color[] cs = { new Color(0,0,0,120), new Color(0,0,0,0) };
                RadialGradientPaint ao = new RadialGradientPaint(p, rr, dist, cs);
                Graphics2D aog = (Graphics2D) g2.create();
                aog.setPaint(ao);
                aog.fill(new Ellipse2D.Float((float)ox - rr, (float)oy - rr, 2*rr, 2*rr));
                aog.dispose();
            }
        }

        private void drawCornerGlows(Graphics2D g2, double[] xs, double[] ys, Color phaseColor){
            for (int i = 0; i < 5; i++) {
                double vx = xs[i], vy = ys[i];
                double gx = vx * 0.98 + xs[(i+1)%5] * 0.02;
                double gy = vy * 0.98 + ys[(i+1)%5] * 0.02;

                float rrOuter = 20f;

                Point2D p = new Point2D.Float((float)gx, (float)gy);
                float[] dist = {0f, 0.4f, 1f};
                Color bright = blend(phaseColor, Color.WHITE, 0.75);
                Color mid    = new Color(bright.getRed(), bright.getGreen(), bright.getBlue(), 120);
                Color edge   = new Color(bright.getRed(), bright.getGreen(), bright.getBlue(),   0);
                Color[] cs   = { new Color(bright.getRed(), bright.getGreen(), bright.getBlue(), 200),
                        mid, edge };

                RadialGradientPaint glow = new RadialGradientPaint(p, rrOuter, dist, cs);
                Composite old = g2.getComposite();
                g2.setComposite(AlphaComposite.SrcOver);
                Graphics2D gg = (Graphics2D) g2.create();
                gg.setPaint(glow);
                gg.fill(new Ellipse2D.Float((float)gx - rrOuter, (float)gy - rrOuter, 2*rrOuter, 2*rrOuter));
                gg.dispose();
                g2.setComposite(old);
            }
        }

        private void drawNeedle(Graphics2D g2, int cx, int cy, int radius){
            double ang = Math.toRadians(rotationDeg - 90);
            double len = radius * 0.9;
            double x2 = cx + len * Math.cos(ang);
            double y2 = cy + len * Math.sin(ang);

            Graphics2D core = (Graphics2D) g2.create();
            core.setStroke(new BasicStroke(3.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            core.setColor(NEEDLE_COLOR);
            core.draw(new Line2D.Double(cx, cy, x2, y2));
            core.dispose();

            Graphics2D dot = (Graphics2D) g2.create();
            dot.setColor(NEEDLE_COLOR);
            int d = 6;
            dot.fill(new Ellipse2D.Double(cx - d/2.0, cy - d/2.0, d, d));
            dot.dispose();
        }

        static Color contrast(Color bg){
            double lum=(0.299*bg.getRed()+0.587*bg.getGreen()+0.114*bg.getBlue())/255.0;
            return lum>0.6?Color.BLACK:Color.WHITE;
        }
        static Color blend(Color a, Color b, double t){
            t=Math.max(0,Math.min(1,t));
            return new Color(
                    (int)Math.round(a.getRed()  +(b.getRed()  -a.getRed())  *t),
                    (int)Math.round(a.getGreen()+(b.getGreen()-a.getGreen())*t),
                    (int)Math.round(a.getBlue() +(b.getBlue() -a.getBlue()) *t)
            );
        }
    }

    /* ---------- Fullscreen helper ---------- */
    static class FullScreenHelper {
        private final JFrame frame;
        private Rectangle prevBounds;
        private boolean wasDecorated;
        private boolean isFullScreen=false;
        private GraphicsDevice fsDevice;
        FullScreenHelper(JFrame f){frame=f;}
        boolean isFullScreen(){return isFullScreen;}
        void toggle(){if(isFullScreen)exitFullScreen();else enterFullScreen();}
        private void enterFullScreen(){
            GraphicsDevice dev=GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
            prevBounds=frame.getBounds(); wasDecorated=frame.isUndecorated(); fsDevice=dev;
            try{
                frame.dispose();
                frame.setUndecorated(true);
                frame.setResizable(false);
                if(dev.isFullScreenSupported()) dev.setFullScreenWindow(frame);
                else{frame.setExtendedState(JFrame.MAXIMIZED_BOTH); frame.setVisible(true);}
                isFullScreen=true;
            }catch(Exception ex){
                frame.setExtendedState(JFrame.MAXIMIZED_BOTH); frame.setVisible(true);
                isFullScreen=true;
            }
        }
        private void exitFullScreen(){
            try{if(fsDevice!=null&&fsDevice.getFullScreenWindow()==frame)fsDevice.setFullScreenWindow(null);}catch(Exception ignore){}
            frame.dispose();
            frame.setUndecorated(wasDecorated);
            frame.setResizable(true);
            if(prevBounds!=null)frame.setBounds(prevBounds);
            frame.setExtendedState(JFrame.NORMAL);
            frame.setVisible(true);
            isFullScreen=false;
        }
    }

    /* ---------- Audio player ---------- */
    static class TonePlayer implements AutoCloseable {
        final float SR=44100f;
        final AudioFormat fmt=new AudioFormat(SR,16,2,true,false);
        final SourceDataLine line;

        private double lastOutL = 0.0, lastOutR = 0.0;
        private boolean haveLastOut = false;
        private static final int RAMP_SAMPLES = 256;
        private static final double MICRO_ATTACK_MS = 2.0;
        private static final double MICRO_RELEASE_MS = 2.0;

        TonePlayer() throws LineUnavailableException {
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);
            line = (SourceDataLine) AudioSystem.getLine(info);
            int preferBuffer = 4096;
            line.open(fmt, preferBuffer);
            line.start();
        }

        void playSimple(double hz,int ms,boolean panLeft,
                        AtomicBoolean paused,AtomicBoolean interrupt,
                        boolean hardCut,
                        Runnable onStart){
            int total=(int)((ms/1000.0)*SR);

            int minAtk = (int)Math.max(1, Math.round(SR*MICRO_ATTACK_MS/1000.0));
            int minRel = (int)Math.max(1, Math.round(SR*MICRO_RELEASE_MS/1000.0));

            int attack  = hardCut ? minAtk : Math.max(minAtk, Math.min((int)(0.08*SR), Math.max(minAtk, total/3)));
            int release = hardCut ? minRel : Math.min((int)(0.25*SR), Math.max(minRel, total/3));

            double step=2*Math.PI*hz/SR;
            double phase=0.0;

            byte[]buf=new byte[512*4]; int sent=0;
            double outL = 0.0, outR = 0.0;
            boolean started=false;

            while(sent<total&&!interrupt.get()){
                int frames=Math.min(512,total-sent),bi=0;
                for(int i=0;i<frames;i++){
                    if(paused.get()){
                        for(int z=0;z<4;z++)buf[bi++]=0;
                        continue;
                    }
                    if(!started){
                        started=true;
                        if(onStart!=null) onStart.run();
                    }

                    int g=sent+i;

                    double env;
                    if (g < attack) env = g / (double)Math.max(1, attack);
                    else if (g > total - release) env = (total - g) / (double)Math.max(1, release);
                    else env = 1.0;

                    double hann = hardCut ? 1.0 : 0.5*(1 - Math.cos(2*Math.PI*g/Math.max(1,total-1)));

                    double pure = Math.sin(phase);
                    double rounded = 0.85*pure + 0.15*Math.sin(phase*0.5);
                    double sVal = rounded * env * hann * 0.32;

                    double l=sVal*(panLeft?0.9:0.6);
                    double r=sVal*(panLeft?0.6:0.9);

                    if (haveLastOut) {
                        int idx = sent + i;
                        if (idx < RAMP_SAMPLES) {
                            double t = 0.5 - 0.5*Math.cos(Math.PI*idx/(RAMP_SAMPLES-1));
                            l = lastOutL*(1-t) + l*t;
                            r = lastOutR*(1-t) + r*t;
                        }
                    }

                    int li=(int)Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(l*32767)));
                    int ri=(int)Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(r*32767)));
                    buf[bi++]=(byte)(li & 0xFF);
                    buf[bi++]=(byte)((li>>>8) & 0xFF);
                    buf[bi++]=(byte)(ri & 0xFF);
                    buf[bi++]=(byte)((ri>>>8) & 0xFF);

                    phase+=step; if(phase>2*Math.PI)phase-=2*Math.PI;
                    outL = l; outR = r;
                }
                line.write(buf,0,frames*4); sent+=frames;
            }

            if (hardCut) { outL = 0.0; outR = 0.0; }
            lastOutL = outL; lastOutR = outR; haveLastOut = true;
        }

        @Override public void close(){
            try{line.drain();}catch(Exception ignore){}
            line.stop(); line.close();
        }
    }

    /* ---------- Main ---------- */
    public static void main(String[] args){
        SwingUtilities.invokeLater(() -> {
            JFrame f = new JFrame("Five-Phase Encoder — v7.12");
            f.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
            f.setLayout(new BorderLayout());

            Panel panel = new Panel();
            f.add(panel, BorderLayout.CENTER);

            // ===== Top controls bar =====
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

            // ONE accent color for all main labels (Rotation color reused)
            final Color ACCENT  = new Color(0xFFAA33); // orange

            // Breath radios (label accented)
            JLabel breathLabel = new JLabel("Breath:");
            breathLabel.setForeground(ACCENT);
            JRadioButton btn6  = new JRadioButton("6 / 4");
            JRadioButton btn8  = new JRadioButton("8 / 2");
            ButtonGroup breathGroup = new ButtonGroup();
            breathGroup.add(btn6); breathGroup.add(btn8);
            btn6.setSelected(true);
            for (JRadioButton b : new JRadioButton[]{btn6, btn8}) {
                b.setFocusable(false); b.setForeground(Color.WHITE); b.setOpaque(false);
            }

            // Transition radios (label accented)
            JLabel transitionLabel = new JLabel("Transition:");
            transitionLabel.setForeground(ACCENT);
            JRadioButton tHard = new JRadioButton("Hard");
            JRadioButton tSoft = new JRadioButton("Soft");
            ButtonGroup transGroup = new ButtonGroup();
            transGroup.add(tHard); transGroup.add(tSoft);
            tSoft.setSelected(true);
            for (JRadioButton b : new JRadioButton[]{tHard, tSoft}) {
                b.setFocusable(false); b.setForeground(Color.WHITE); b.setOpaque(false);
            }

            // Speed radios (label accented)
            JLabel speedLabel = new JLabel("Speed:");
            speedLabel.setForeground(ACCENT);
            JRadioButton spFast = new JRadioButton("10 s");
            JRadioButton sp15   = new JRadioButton("15 s");
            JRadioButton spMed  = new JRadioButton("60 s");
            JRadioButton spDeep = new JRadioButton("120 s");
            ButtonGroup speedGroup = new ButtonGroup();
            speedGroup.add(spFast); speedGroup.add(sp15); speedGroup.add(spMed); speedGroup.add(spDeep);
            spFast.setSelected(true);
            for (JRadioButton b : new JRadioButton[]{spFast, sp15, spMed, spDeep}) {
                b.setFocusable(false); b.setForeground(Color.WHITE); b.setOpaque(false);
            }

            // Rotation radios (label accented)
            JLabel rotLabel = new JLabel("Rotation:");
            rotLabel.setForeground(ACCENT);
            JRadioButton rotNone = new JRadioButton("No motion");
            JRadioButton rotCont = new JRadioButton("Continuous");
            JRadioButton rotKin  = new JRadioButton("Kinetic 72°");
            ButtonGroup rotGroup = new ButtonGroup();
            rotGroup.add(rotNone); rotGroup.add(rotCont); rotGroup.add(rotKin);
            rotCont.setSelected(true);
            for (JRadioButton b : new JRadioButton[]{rotNone, rotCont, rotKin}) {
                b.setFocusable(false); b.setForeground(Color.WHITE); b.setOpaque(false);
            }

            // Add to bar
            controls.add(breathLabel); controls.add(btn6); controls.add(btn8);
            controls.add(new JSeparator(SwingConstants.VERTICAL));
            controls.add(transitionLabel); controls.add(tHard); controls.add(tSoft);
            controls.add(new JSeparator(SwingConstants.VERTICAL));
            controls.add(speedLabel); controls.add(spFast); controls.add(sp15); controls.add(spMed); controls.add(spDeep);
            controls.add(new JSeparator(SwingConstants.VERTICAL));
            controls.add(rotLabel); controls.add(rotNone); controls.add(rotCont); controls.add(rotKin);
            f.add(controls, BorderLayout.NORTH);
            // ===== End controls bar =====

            // listeners — restart to 12 o'clock when any mode changes
            btn6.addActionListener(e -> panel.setBreathMode(BreathMode.INHALE_6));
            btn8.addActionListener(e -> panel.setBreathMode(BreathMode.INHALE_8));
            tHard.addActionListener(e -> panel.setTransitionMode(TransitionMode.HARD_CUT));
            tSoft.addActionListener(e -> panel.setTransitionMode(TransitionMode.SOFT));
            spFast.addActionListener(e -> panel.setSpeedMode(SpeedMode.FAST_10S));
            sp15.addActionListener(e -> panel.setSpeedMode(SpeedMode.BALANCED_15S));
            spMed.addActionListener(e -> panel.setSpeedMode(SpeedMode.MEDITATIVE_60S));
            spDeep.addActionListener(e -> panel.setSpeedMode(SpeedMode.DEEP_120S));
            rotNone.addActionListener(e -> panel.setRotationMode(RotationMode.NO_MOTION));
            rotCont.addActionListener(e -> panel.setRotationMode(RotationMode.CONTINUOUS));
            rotKin.addActionListener(e -> panel.setRotationMode(RotationMode.KINETIC_STEP));

            AtomicBoolean paused = new AtomicBoolean(false);
            FullScreenHelper fs = new FullScreenHelper(f);

            f.pack(); f.setLocationRelativeTo(null); f.setVisible(true);

            /* ---------- Key bindings ---------- */
            JRootPane root=f.getRootPane();
            InputMap im=root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
            ActionMap am=root.getActionMap();

            Runnable toggleFS = fs::toggle;
            Runnable togglePause = () -> {
                boolean now = !paused.get();
                paused.set(now);
                panel.setPausedVisual(now);
            };
            Runnable toggleHUD = () -> { panel.showHud = !panel.showHud; controls.setVisible(panel.showHud); panel.repaint(); };
            Runnable escAction = () -> {
                if (fs.isFullScreen()) fs.toggle();
                else f.dispatchEvent(new WindowEvent(f, WindowEvent.WINDOW_CLOSING));
            };

            KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
                if (e.getID() == KeyEvent.KEY_PRESSED && e.getKeyCode() == KeyEvent.VK_ENTER) {
                    int m = e.getModifiersEx();
                    boolean alt     = (m & InputEvent.ALT_DOWN_MASK) != 0;
                    boolean altGr   = (m & InputEvent.ALT_GRAPH_DOWN_MASK) != 0;
                    boolean ctrlAlt = (m & InputEvent.CTRL_DOWN_MASK) != 0 && (m & InputEvent.ALT_DOWN_MASK) != 0;
                    if (alt || altGr || ctrlAlt) { toggleFS.run(); return true; }
                }
                return false;
            });

            // F11, Alt+Enter
            im.put(KeyStroke.getKeyStroke("F11"), "fs");
            am.put("fs", new AbstractAction(){ public void actionPerformed(ActionEvent e){ toggleFS.run(); }});
            im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.ALT_DOWN_MASK, false), "fsAltPressed");
            am.put("fsAltPressed", new AbstractAction(){ public void actionPerformed(ActionEvent e){ toggleFS.run(); }});

            // Other keys
            im.put(KeyStroke.getKeyStroke("SPACE"), "pause");
            im.put(KeyStroke.getKeyStroke("H"), "hud");

            im.put(KeyStroke.getKeyStroke("1"), "b6");
            im.put(KeyStroke.getKeyStroke("2"), "b8");

            im.put(KeyStroke.getKeyStroke("3"), "tHard");
            im.put(KeyStroke.getKeyStroke("4"), "tSoft");

            im.put(KeyStroke.getKeyStroke("5"), "fast");
            im.put(KeyStroke.getKeyStroke("L"), "fifteen");
            im.put(KeyStroke.getKeyStroke("6"), "med");
            im.put(KeyStroke.getKeyStroke("9"), "deep");

            im.put(KeyStroke.getKeyStroke("7"), "rotCont");
            im.put(KeyStroke.getKeyStroke("8"), "rotKin");
            im.put(KeyStroke.getKeyStroke("0"), "rotNone");

            im.put(KeyStroke.getKeyStroke("ESCAPE"), "esc");

            am.put("pause",   new AbstractAction(){ public void actionPerformed(ActionEvent e){ togglePause.run(); }});
            am.put("hud",     new AbstractAction(){ public void actionPerformed(ActionEvent e){ toggleHUD.run(); }});

            am.put("b6",      new AbstractAction(){ public void actionPerformed(ActionEvent e){ panel.setBreathMode(BreathMode.INHALE_6); btn6.setSelected(true); }});
            am.put("b8",      new AbstractAction(){ public void actionPerformed(ActionEvent e){ panel.setBreathMode(BreathMode.INHALE_8); btn8.setSelected(true); }});

            am.put("tHard",   new AbstractAction(){ public void actionPerformed(ActionEvent e){ panel.setTransitionMode(TransitionMode.HARD_CUT); tHard.setSelected(true); }});
            am.put("tSoft",   new AbstractAction(){ public void actionPerformed(ActionEvent e){ panel.setTransitionMode(TransitionMode.SOFT); tSoft.setSelected(true); }});

            am.put("fast",    new AbstractAction(){ public void actionPerformed(ActionEvent e){ panel.setSpeedMode(SpeedMode.FAST_10S); spFast.setSelected(true); }});
            am.put("fifteen", new AbstractAction(){ public void actionPerformed(ActionEvent e){ panel.setSpeedMode(SpeedMode.BALANCED_15S); sp15.setSelected(true); }});
            am.put("med",     new AbstractAction(){ public void actionPerformed(ActionEvent e){ panel.setSpeedMode(SpeedMode.MEDITATIVE_60S); spMed.setSelected(true); }});
            am.put("deep",    new AbstractAction(){ public void actionPerformed(ActionEvent e){ panel.setSpeedMode(SpeedMode.DEEP_120S); spDeep.setSelected(true); }});

            am.put("rotCont", new AbstractAction(){ public void actionPerformed(ActionEvent e){ panel.setRotationMode(RotationMode.CONTINUOUS); rotCont.setSelected(true); }});
            am.put("rotKin",  new AbstractAction(){ public void actionPerformed(ActionEvent e){ panel.setRotationMode(RotationMode.KINETIC_STEP); rotKin.setSelected(true); }});
            am.put("rotNone", new AbstractAction(){ public void actionPerformed(ActionEvent e){ panel.setRotationMode(RotationMode.NO_MOTION); rotNone.setSelected(true); }});

            am.put("esc",     new AbstractAction(){ public void actionPerformed(ActionEvent e){ escAction.run(); }});

            /* ---------- Audio/visual loop (SYNCED) ---------- */
            AtomicBoolean interrupt=new AtomicBoolean(false);
            Thread loop=new Thread(() -> {
                try(TonePlayer tp=new TonePlayer()){
                    int idx=0;
                    boolean firstRun = true;

                    panel.resetToTop();

                    while(!interrupt.get()){
                        while (paused.get() && !interrupt.get()) {
                            try { Thread.sleep(20); } catch (InterruptedException ignored) {}
                        }
                        if (interrupt.get()) break;

                        if (!firstRun && panel.resetRequested.getAndSet(false)) {
                            idx = 0;
                            panel.resetToTop();
                        }

                        Phase p   = PHASES[idx];
                        Phase prev= PHASES[(idx+PHASES.length-1)%PHASES.length];

                        boolean inhalePhase = isInhale(panel.breathMode, idx);
                        boolean panLeft = !inhalePhase;

                        boolean hard = (panel.transition == TransitionMode.HARD_CUT);
                        final int phaseIdxFinal = idx;
                        int ms = panel.perPhaseMs();

                        tp.playSimple(p.hz, ms, panLeft, paused, interrupt, hard,
                                () -> panel.setPhaseAtAudioStart(p, prev.color, phaseIdxFinal)
                        );

                        if (panel.rotationMode != RotationMode.NO_MOTION) {
                            panel.currentAngleDeg = (panel.currentAngleDeg + 72.0) % 360.0;
                        }

                        idx=(idx+1)%PHASES.length;
                        firstRun = false;
                    }
                }catch(Exception ex){ex.printStackTrace();}
            }, "phase-loop");

            f.addWindowListener(new WindowAdapter(){@Override public void windowClosing(WindowEvent e){interrupt.set(true);}});
            loop.setDaemon(true); loop.start();
        });
    }

    private static boolean isInhale(BreathMode mode,int phaseIndex){
        switch(mode){
            case INHALE_8: return phaseIndex<=3; // 4 phases inhale (8s in 10s block)
            case INHALE_6:
            default: return phaseIndex<=2;       // 3 phases inhale (6s in 10s block)
        }
    }
}
