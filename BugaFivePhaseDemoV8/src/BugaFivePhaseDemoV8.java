// BugaFivePhaseDemoV8.java
//Designed by Seyedrasool Sadrieh @Mesgona November 2025
// v8.4 — Synced phase color (bg + pentagon) + breath ratios + phase title
//
// • One phase = one full breath (inhale + exhale).
// • Breath styles (inhale / exhale):
//      COHERENT   — 50% / 50%
//      RELAXED    — 60% / 40%
//      DEEP_CALM  — 67% / 33%
// • Five speed modes (loop = 5 phases):
//      IGNITE     — 10s loop  ( 2s / phase)
//      BALANCE    — 20s loop  ( 4s / phase)
//      HARMONY    — 30s loop  ( 6s / phase)
//      ZEN        — 60s loop  (12s / phase)
//      TRANSCEND  — 120s loop (24s / phase)
// • HARD transition: instant color/tone change at phase boundary.
// • SOFT transition: whole breath is one phase color/tone; only a short
//   fade (max ~600 ms, or ~20% of phase) at the *end* of the phase.
// • Phase name shown in big text under pentagon when HUD is ON.
//   HUD OFF => only pentagon + colors + rotation (no text, no counters).
//
// Keys:
//
// Global:
//   [SPACE] Pause / Resume
//   [H]     Toggle HUD (also hides/shows top bar)
//   [F11] or [Alt+Enter] Fullscreen
//   [Esc]  Exit (or exit fullscreen first)
//
// Speed modes (loop length):
//   [1] IGNITE     — 10s loop ( 2s / phase)
//   [2] BALANCE    — 20s loop ( 4s / phase)
//   [3] HARMONY    — 30s loop ( 6s / phase)
//   [4] ZEN        — 60s loop (12s / phase)
//   [5] TRANSCEND  — 120s loop (24s / phase)
//
// Breath style (inhale/exhale):
//   [Q] Coherent   — 50/50
//   [W] Relaxed    — 60/40
//   [E] Deep Calm  — 67/33
//
// Transition:
//   [T] Hard cut
//   [Y] Soft fade
//
// Rotation:
//   [7] Continuous
//   [8] Kinetic 72° steps
//   [0] No motion

import javax.sound.sampled.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.geom.Point2D;
import java.util.concurrent.atomic.AtomicBoolean;

public class BugaFivePhaseDemoV8 {

    /* ---------- Phase model ---------- */
    static class Phase {
        final String name;
        final Color color;
        final double hz;
        Phase(String n, Color c, double f) { name = n; color = c; hz = f; }
    }

    static final Phase[] PHASES = {
            new Phase("Origin",  new Color(0xFFFFFF), 440.0),
            new Phase("Growth",  new Color(0x00FFFF), 494.0),
            new Phase("Peak",    new Color(0x00FF00), 556.0),
            new Phase("Decline", new Color(0xFFBF00), 624.0),
            new Phase("Renewal", new Color(0xFF00FF), 702.0)
    };

    enum BreathStyle {
        COHERENT,   // 50% / 50%
        RELAXED,    // 60% / 40%
        DEEP_CALM   // 67% / 33%
    }

    enum TransitionMode { HARD_CUT, SOFT }

    enum SpeedMode {
        IGNITE,      // 10s loop -> 2s / phase
        BALANCE,     // 20s loop -> 4s / phase
        HARMONY,     // 30s loop -> 6s / phase
        ZEN,         // 60s loop -> 12s / phase
        TRANSCEND    // 120s loop -> 24s / phase
    }

    enum RotationMode { CONTINUOUS, KINETIC_STEP, NO_MOTION }

    /* ---------- Visual panel ---------- */
    static class Panel extends JPanel {
        Phase cur = PHASES[0];
        int phaseIndex = 0;
        boolean showHud = true;

        BreathStyle   breathStyle   = BreathStyle.COHERENT;
        TransitionMode transition   = TransitionMode.SOFT;
        SpeedMode     speedMode     = SpeedMode.IGNITE;
        RotationMode  rotationMode  = RotationMode.CONTINUOUS;

        // For soft transitions: fade current phase color -> next phase color
        private Color fadeFrom = PHASES[0].color;
        private Color fadeTo   = PHASES[1].color;

        // Phase timing (per speed + breath style)
        int    phaseMsCurrent   = 2000;
        volatile double inhaleFrac = 0.5; // 0–1
        int    inhaleMsCurrent  = 1000;
        int    exhaleMsCurrent  = 1000;

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

        final AtomicBoolean resetRequested = new AtomicBoolean(false);

        // Fixed gray for outline & needle
        private static final Color PENTA_EDGE   = new Color(0x5A5A5A);
        private static final Color NEEDLE_COLOR = new Color(0x5A5A5A);

        private long phaseStartNanos = 0L;

        Panel() {
            setBackground(new Color(20, 20, 20));
            setDoubleBuffered(true);
            updateTiming();
        }

        int perPhaseMs() {
            switch (speedMode) {
                case BALANCE:    return 4000;   // 20s loop / 5
                case HARMONY:    return 6000;   // 30s loop / 5
                case ZEN:        return 12000;  // 60s loop / 5
                case TRANSCEND:  return 24000;  // 120s loop / 5
                case IGNITE:
                default:         return 2000;   // 10s loop / 5
            }
        }

        int loopSeconds() {
            return (perPhaseMs() * PHASES.length) / 1000;
        }

        long loopPeriodNanos() {
            return (long) perPhaseMs() * PHASES.length * 1_000_000L;
        }

        void updateTiming() {
            phaseMsCurrent = perPhaseMs();

            switch (breathStyle) {
                case RELAXED:
                    // 60% inhale / 40% exhale
                    inhaleFrac = 0.60;
                    break;
                case DEEP_CALM:
                    // 67% inhale / 33% exhale
                    inhaleFrac = 0.67;
                    break;
                case COHERENT:
                default:
                    // 50% inhale / 50% exhale
                    inhaleFrac = 0.50;
                    break;
            }

            inhaleMsCurrent = (int) Math.round(phaseMsCurrent * inhaleFrac);
            inhaleMsCurrent = Math.max(1, Math.min(phaseMsCurrent - 1, inhaleMsCurrent));
            exhaleMsCurrent = phaseMsCurrent - inhaleMsCurrent;
        }

        final Timer anim = new Timer(16, e -> repaint());

        void setPausedVisual(boolean paused) {
            if (this.pausedVisual == paused) return;
            this.pausedVisual = paused;
            if (paused) {
                pausedAtNanos = System.nanoTime();
                if (anim.isRunning()) anim.stop();
            } else {
                long now = System.nanoTime();
                long delta = now - pausedAtNanos;
                pausedAccumNanos += delta;
                if (phaseStartNanos != 0) phaseStartNanos += delta;
                if (!anim.isRunning()) anim.start();
            }
            repaint();
        }

        void resetToTop() {
            updateTiming();
            phaseIndex = 0;
            cur = PHASES[0];

            currentAngleDeg = 0.0;
            rotStartDeg = 0.0;
            rotTargetDeg = (rotationMode == RotationMode.NO_MOTION) ? 0.0 : 72.0;
            rotationDeg = 0.0;

            // For soft, we fade from first phase to second phase at the boundary
            fadeFrom = PHASES[0].color;
            fadeTo   = PHASES[1].color;

            phaseStartNanos = pausedVisual ? pausedAtNanos : System.nanoTime();

            if (!anim.isRunning() && !pausedVisual) anim.start();
        }

        void setPhaseAtAudioStart(Phase p, Color nextColor, int idx) {
            // Update timing first, in case speed or breath style changed
            updateTiming();

            cur = p;
            phaseIndex = idx;

            // Rotation targets
            if (rotationMode == RotationMode.NO_MOTION) {
                rotStartDeg  = currentAngleDeg % 360.0;
                rotTargetDeg = currentAngleDeg % 360.0;
            } else {
                rotStartDeg  = currentAngleDeg % 360.0;
                rotTargetDeg = (currentAngleDeg + 72.0) % 360.0;
            }

            // Phase start
            phaseStartNanos = pausedVisual ? pausedAtNanos : System.nanoTime();

            // For SOFT: this phase's color -> next phase's color
            fadeFrom = p.color;
            fadeTo   = nextColor;

            if (!pausedVisual && !anim.isRunning()) anim.start();
        }

        void setBreathStyle(BreathStyle style) {
            breathStyle = style;
            resetRequested.set(true);
        }

        void setTransitionMode(TransitionMode tm) {
            transition = tm;
            resetRequested.set(true);
        }

        void setSpeedMode(SpeedMode sm) {
            speedMode = sm;
            resetRequested.set(true);
        }

        void setRotationMode(RotationMode rm) {
            rotationMode = rm;
            resetRequested.set(true);
        }

        long overallMillis() {
            long now = pausedVisual ? pausedAtNanos : System.nanoTime();
            long eff = now - appStartNanos - pausedAccumNanos;
            return Math.max(0L, eff / 1_000_000L);
        }

        long loopMillis() {
            long now = pausedVisual ? pausedAtNanos : System.nanoTime();
            long eff = now - appStartNanos - pausedAccumNanos;
            long perLoop = loopPeriodNanos();
            long mod = ((eff % perLoop) + perLoop) % perLoop;
            return mod / 1_000_000L;
        }

        static String fmtMillis(long ms) {
            long s = ms / 1000, m = s / 60, h = m / 60;
            s %= 60;
            m %= 60;
            return String.format("%02d:%02d:%02d", h, m, s);
        }

        static String fmtSec1(long ms) {
            return String.format("%.1f", ms / 1000.0);
        }

        static double smooth(double t) {
            t = Math.max(0, Math.min(1, t));
            return t * t * (3 - 2 * t);
        }

        static double lerpDeg(double a, double b, double t) {
            double da = ((b - a + 540) % 360) - 180;
            return (a + da * t + 360) % 360;
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(1000, 640);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            long nowN = pausedVisual ? pausedAtNanos : System.nanoTime();
            long phaseElapsedMs = (phaseStartNanos == 0L)
                    ? 0L
                    : Math.max(0L, (nowN - phaseStartNanos) / 1_000_000L);

            boolean inInhale = phaseElapsedMs < inhaleMsCurrent;

            /* ---------- Shared phaseColor (bg + pentagon) ---------- */
            Color phaseColor;
            if (transition == TransitionMode.SOFT) {
                // Short fade only near END of the phase
                int fadeLen   = Math.min(600, phaseMsCurrent / 5); // up to ~20% or max 600ms
                int fadeStart = Math.max(0, phaseMsCurrent - fadeLen);

                double fadeT;
                if (phaseElapsedMs <= fadeStart) {
                    fadeT = 0.0; // most of the phase = solid phase color
                } else {
                    double raw = (phaseElapsedMs - fadeStart) / (double) Math.max(1, fadeLen);
                    fadeT = Math.max(0.0, Math.min(1.0, raw));
                    fadeT = smooth(fadeT);
                }
                phaseColor = blend(fadeFrom, fadeTo, fadeT);
            } else {
                // HARD: no fade, just current phase color
                phaseColor = cur.color;
            }

            Color bg = phaseColor;

            g2.setComposite(AlphaComposite.SrcOver);
            g2.setColor(bg);
            g2.fillRect(0, 0, getWidth(), getHeight());

            // Rotation
            if (rotationMode == RotationMode.NO_MOTION) {
                rotationDeg = currentAngleDeg % 360.0;
            } else if (phaseStartNanos == 0L) {
                rotationDeg = currentAngleDeg % 360.0;
            } else if (rotationMode == RotationMode.KINETIC_STEP) {
                double ms = (nowN - phaseStartNanos) / 1e6;
                double u = smooth(Math.min(1.0, ms / ROTATE_ANIM_MS));
                rotationDeg = lerpDeg(rotStartDeg, rotTargetDeg, u);
            } else {
                double ms = (nowN - phaseStartNanos) / 1e6;
                double u = Math.max(0, Math.min(1, ms / Math.max(1, phaseMsCurrent)));
                rotationDeg = lerpDeg(rotStartDeg, rotTargetDeg, u);
            }

            int cx = getWidth() / 2;
            int cy = getHeight() / 2;
            int radius = Math.min(getWidth(), getHeight()) / 4;

            Polygon poly = new Polygon();
            double[] xs = new double[5];
            double[] ys = new double[5];
            for (int i = 0; i < 5; i++) {
                double ang = Math.toRadians(rotationDeg + i * 72 - 90);
                double x = cx + radius * Math.cos(ang);
                double y = cy + radius * Math.sin(ang);
                xs[i] = x;
                ys[i] = y;
                poly.addPoint((int) Math.round(x), (int) Math.round(y));
            }

            // Drop shadow
            Graphics2D s = (Graphics2D) g2.create();
            s.translate(4, 6);
            s.setColor(new Color(0, 0, 0, 70));
            s.fillPolygon(poly);
            s.dispose();

            // Phase-colored 3D fill (using phaseColor so it's synced with bg)
            Color base   = phaseColor;
            Color lighter = blend(base, Color.WHITE, 0.55);
            Color darker  = blend(base, Color.BLACK, 0.35);
            float r = (float) (radius * 1.25);
            Point2D center = new Point2D.Float((float) (cx - radius * 0.35), (float) (cy - radius * 0.35));
            float[] dist = {0f, 0.6f, 1f};
            Color[] cols = {lighter, base, darker};
            RadialGradientPaint rgp = new RadialGradientPaint(center, r, dist, cols);
            g2.setPaint(rgp);
            g2.fillPolygon(poly);

            // AO + bright corner glows (also driven by phaseColor)
            drawVertexAO(g2, xs, ys, cx, cy);
            drawCornerGlows(g2, xs, ys, phaseColor);

            // Outline
            g2.setStroke(new BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(PENTA_EDGE);
            g2.drawPolygon(poly);

            // Needle (hidden in NO_MOTION)
            if (rotationMode != RotationMode.NO_MOTION) {
                drawNeedle(g2, cx, cy, radius);
            }

            // HUD elements
            if (showHud) {
                // Breath word
                String breathWordNow = inInhale ? "INHALE" : "EXHALE";
                g2.setFont(getFont().deriveFont(Font.BOLD, Math.max(64f, getWidth() * 0.09f)));
                Color overlay = (contrast(bg) == Color.BLACK)
                        ? new Color(0, 0, 0, 120)
                        : new Color(255, 255, 255, 160);
                g2.setColor(overlay);
                int bw = g2.getFontMetrics().stringWidth(breathWordNow);
                g2.drawString(breathWordNow, (getWidth() - bw) / 2, (int) (getHeight() * 0.30));

                // Big phase name under pentagon
                g2.setFont(getFont().deriveFont(Font.BOLD, Math.max(36f, getWidth() * 0.035f)));
                String phaseLabel = cur.name;
                int pw = g2.getFontMetrics().stringWidth(phaseLabel);
                int phaseY = cy + radius + 60; // just below pentagon
                g2.drawString(phaseLabel, (getWidth() - pw) / 2, phaseY);

                // Breath countdown
                int segmentMs = inInhale ? inhaleMsCurrent : exhaleMsCurrent;
                int segPosMs  = inInhale
                        ? (int) Math.min(segmentMs, Math.max(0, phaseElapsedMs))
                        : (int) Math.min(segmentMs, Math.max(0, phaseElapsedMs - inhaleMsCurrent));
                int remaining = (int) Math.ceil((segmentMs - segPosMs) / 1000.0);

                drawBreathCountdownChip(g2, cx, cy, radius, remaining, inInhale, bg);

                // Header + footer + chips
                g2.setColor(contrast(bg));
                g2.setFont(getFont().deriveFont(Font.BOLD, 24f));
                g2.drawString("Five-Phase Encoder", 24, 40);
                g2.setFont(getFont().deriveFont(Font.PLAIN, 18f));
                g2.drawString("Phase tone: " + (int) cur.hz + " Hz", 24, 68);

                g2.setColor(contrast(bg));
                g2.setFont(getFont().deriveFont(Font.PLAIN, 14f));

                String loopCap = " / " + loopSeconds() + ".0s loop";
                String loopStr = "Loop: " + fmtSec1(loopMillis()) + loopCap;
                String totalStr = "Total: " + fmtMillis(overallMillis());

                String breathStyleStr;
                switch (breathStyle) {
                    case RELAXED:
                        breathStyleStr = "Relaxed 60/40";
                        break;
                    case DEEP_CALM:
                        breathStyleStr = "Deep Calm 67/33";
                        break;
                    case COHERENT:
                    default:
                        breathStyleStr = "Coherent 50/50";
                        break;
                }

                String help =
                        "[F11 / Alt+Enter] Full Screen   [SPACE] Pause   [H] HUD   " +
                                "[Q/W/E] Breath Style   [1–5] Speed   [T/Y] Transition   [7/8/0] Rotation   [Esc] Exit";

                String footer = loopStr + "    " + totalStr + "    " + breathStyleStr + "    " + help;

                int wFooter = g2.getFontMetrics().stringWidth(footer);
                g2.drawString(footer, Math.max(24, getWidth() / 2 - wFooter / 2), getHeight() - 24);

                // Right chips
                String modeLbl = "Breath: " + breathStyleStr;
                String transLbl = "Transition: " + (transition == TransitionMode.HARD_CUT ? "Hard" : "Soft");

                String speedLbl;
                switch (speedMode) {
                    case BALANCE:
                        speedLbl = "Speed: BALANCE (" + loopSeconds() + "s loop)";
                        break;
                    case HARMONY:
                        speedLbl = "Speed: HARMONY (" + loopSeconds() + "s loop)";
                        break;
                    case ZEN:
                        speedLbl = "Speed: ZEN (" + loopSeconds() + "s loop)";
                        break;
                    case TRANSCEND:
                        speedLbl = "Speed: TRANSCEND (" + loopSeconds() + "s loop)";
                        break;
                    case IGNITE:
                    default:
                        speedLbl = "Speed: IGNITE (" + loopSeconds() + "s loop)";
                        break;
                }

                String rotLbl = "Rotation: " +
                        (rotationMode == RotationMode.NO_MOTION ? "No motion"
                                : rotationMode == RotationMode.CONTINUOUS ? "Continuous"
                                : "Kinetic 72°");

                g2.setFont(getFont().deriveFont(Font.PLAIN, 14f));
                int xRight = getWidth() - 24;

                int w1 = g2.getFontMetrics().stringWidth(modeLbl) + 20;
                int w2 = g2.getFontMetrics().stringWidth(transLbl) + 20;
                int w3 = g2.getFontMetrics().stringWidth(speedLbl) + 20;
                int w4 = g2.getFontMetrics().stringWidth(rotLbl) + 20;

                g2.setColor(new Color(0, 0, 0, 80));
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
                                             int remaining, boolean inInhale, Color bg) {
            int boxH = 64;
            int boxW = 140;
            int x = cx + radius + 40;
            int y = cy - boxH / 2;

            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(0, 0, 0, 90));
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

        private void drawVertexAO(Graphics2D g2, double[] xs, double[] ys, int cx, int cy) {
            for (int i = 0; i < 5; i++) {
                double vx = xs[i], vy = ys[i];
                double ox = cx + (vx - cx) * 0.92;
                double oy = cy + (vy - cy) * 0.92;
                float rr = 18f;
                Point2D p = new Point2D.Float((float) ox, (float) oy);
                float[] dist = {0f, 1f};
                Color[] cs = { new Color(0, 0, 0, 120), new Color(0, 0, 0, 0) };
                RadialGradientPaint ao = new RadialGradientPaint(p, rr, dist, cs);
                Graphics2D aog = (Graphics2D) g2.create();
                aog.setPaint(ao);
                aog.fill(new Ellipse2D.Float((float) ox - rr, (float) oy - rr, 2 * rr, 2 * rr));
                aog.dispose();
            }
        }

        private void drawCornerGlows(Graphics2D g2, double[] xs, double[] ys, Color phaseColor) {
            for (int i = 0; i < 5; i++) {
                double vx = xs[i], vy = ys[i];
                double gx = vx * 0.98 + xs[(i + 1) % 5] * 0.02;
                double gy = vy * 0.98 + ys[(i + 1) % 5] * 0.02;

                float rrOuter = 20f;

                Point2D p = new Point2D.Float((float) gx, (float) gy);
                float[] dist = {0f, 0.4f, 1f};
                Color bright = blend(phaseColor, Color.WHITE, 0.75);
                Color mid    = new Color(bright.getRed(), bright.getGreen(), bright.getBlue(), 120);
                Color edge   = new Color(bright.getRed(), bright.getGreen(), bright.getBlue(),   0);
                Color[] cs   = {
                        new Color(bright.getRed(), bright.getGreen(), bright.getBlue(), 200),
                        mid, edge
                };

                RadialGradientPaint glow = new RadialGradientPaint(p, rrOuter, dist, cs);
                Composite old = g2.getComposite();
                g2.setComposite(AlphaComposite.SrcOver);
                Graphics2D gg = (Graphics2D) g2.create();
                gg.setPaint(glow);
                gg.fill(new Ellipse2D.Float((float) gx - rrOuter, (float) gy - rrOuter, 2 * rrOuter, 2 * rrOuter));
                gg.dispose();
                g2.setComposite(old);
            }
        }

        private void drawNeedle(Graphics2D g2, int cx, int cy, int radius) {
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
            dot.fill(new Ellipse2D.Double(cx - d / 2.0, cy - d / 2.0, d, d));
            dot.dispose();
        }

        static Color contrast(Color bg) {
            double lum = (0.299 * bg.getRed() + 0.587 * bg.getGreen() + 0.114 * bg.getBlue()) / 255.0;
            return lum > 0.6 ? Color.BLACK : Color.WHITE;
        }

        static Color blend(Color a, Color b, double t) {
            t = Math.max(0, Math.min(1, t));
            return new Color(
                    (int) Math.round(a.getRed()   + (b.getRed()   - a.getRed())   * t),
                    (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                    (int) Math.round(a.getBlue()  + (b.getBlue()  - a.getBlue())  * t)
            );
        }
    }

    /* ---------- Fullscreen helper ---------- */
    static class FullScreenHelper {
        private final JFrame frame;
        private Rectangle prevBounds;
        private boolean wasDecorated;
        private boolean isFullScreen = false;
        private GraphicsDevice fsDevice;

        FullScreenHelper(JFrame f) { frame = f; }

        boolean isFullScreen() { return isFullScreen; }

        void toggle() {
            if (isFullScreen) exitFullScreen();
            else enterFullScreen();
        }

        private void enterFullScreen() {
            GraphicsDevice dev = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
            prevBounds = frame.getBounds();
            wasDecorated = frame.isUndecorated();
            fsDevice = dev;
            try {
                frame.dispose();
                frame.setUndecorated(true);
                frame.setResizable(false);
                if (dev.isFullScreenSupported()) dev.setFullScreenWindow(frame);
                else {
                    frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
                    frame.setVisible(true);
                }
                isFullScreen = true;
            } catch (Exception ex) {
                frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
                frame.setVisible(true);
                isFullScreen = true;
            }
        }

        private void exitFullScreen() {
            try {
                if (fsDevice != null && fsDevice.getFullScreenWindow() == frame) {
                    fsDevice.setFullScreenWindow(null);
                }
            } catch (Exception ignore) {}
            frame.dispose();
            frame.setUndecorated(wasDecorated);
            frame.setResizable(true);
            if (prevBounds != null) frame.setBounds(prevBounds);
            frame.setExtendedState(JFrame.NORMAL);
            frame.setVisible(true);
            isFullScreen = false;
        }
    }

    /* ---------- Audio player ---------- */
    static class TonePlayer implements AutoCloseable {
        final float SR = 44100f;
        final AudioFormat fmt = new AudioFormat(SR, 16, 2, true, false);
        final SourceDataLine line;

        private double lastOutL = 0.0, lastOutR = 0.0;
        private boolean haveLastOut = false;
        private static final int RAMP_SAMPLES = 256;
        private static final double MICRO_ATTACK_MS  = 2.0;
        private static final double MICRO_RELEASE_MS = 2.0;

        TonePlayer() throws LineUnavailableException {
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);
            line = (SourceDataLine) AudioSystem.getLine(info);
            int preferBuffer = 4096;
            line.open(fmt, preferBuffer);
            line.start();
        }

        void playSimple(double hz,
                        int ms,
                        double inhaleFrac,          // 0–1: fraction of phase as INHALE
                        AtomicBoolean paused,
                        AtomicBoolean interrupt,
                        boolean hardCut,
                        Runnable onStart) {

            int total = (int) ((ms / 1000.0) * SR);

            int minAtk = (int) Math.max(1, Math.round(SR * MICRO_ATTACK_MS / 1000.0));
            int minRel = (int) Math.max(1, Math.round(SR * MICRO_RELEASE_MS / 1000.0));

            int attack  = hardCut ? minAtk
                    : Math.max(minAtk, Math.min((int) (0.08 * SR), Math.max(minAtk, total / 3)));
            int release = hardCut ? minRel
                    : Math.min((int) (0.25 * SR), Math.max(minRel, total / 3));

            double step  = 2 * Math.PI * hz / SR;
            double phase = 0.0;

            byte[] buf = new byte[512 * 4];
            int sent = 0;
            double outL = 0.0, outR = 0.0;
            boolean started = false;

            while (sent < total && !interrupt.get()) {
                int frames = Math.min(512, total - sent);
                int bi = 0;

                for (int i = 0; i < frames; i++) {
                    if (paused.get()) {
                        for (int z = 0; z < 4; z++) buf[bi++] = 0;
                        continue;
                    }

                    if (!started) {
                        started = true;
                        if (onStart != null) onStart.run();
                    }

                    int g = sent + i;

                    // Envelope
                    double env;
                    if (g < attack) {
                        env = g / (double) Math.max(1, attack);
                    } else if (g > total - release) {
                        env = (total - g) / (double) Math.max(1, release);
                    } else {
                        env = 1.0;
                    }

                    // Hann window for soft shaping
                    double hann = hardCut ? 1.0 : 0.5 * (1 - Math.cos(2 * Math.PI * g / Math.max(1, total - 1)));

                    // Base waveform
                    double pure = Math.sin(phase);
                    double rounded = 0.85 * pure + 0.15 * Math.sin(phase * 0.5);
                    double sVal = rounded * env * hann * 0.32;

                    // Inhale vs exhale panning over the phase
                    double tPhase = total <= 0 ? 0.0 : (g / (double) total);
                    boolean inInhale = tPhase < inhaleFrac;

                    double l = sVal * (inInhale ? 0.90 : 0.60);
                    double r = sVal * (inInhale ? 0.60 : 0.90);

                    // Micro-crossfade with last frame to avoid clicks
                    if (haveLastOut) {
                        int idx = sent + i;
                        if (idx < RAMP_SAMPLES) {
                            double t = 0.5 - 0.5 * Math.cos(Math.PI * idx / (RAMP_SAMPLES - 1));
                            l = lastOutL * (1 - t) + l * t;
                            r = lastOutR * (1 - t) + r * t;
                        }
                    }

                    int li = (int) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(l * 32767)));
                    int ri = (int) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(r * 32767)));
                    buf[bi++] = (byte) (li & 0xFF);
                    buf[bi++] = (byte) ((li >>> 8) & 0xFF);
                    buf[bi++] = (byte) (ri & 0xFF);
                    buf[bi++] = (byte) ((ri >>> 8) & 0xFF);

                    phase += step;
                    if (phase > 2 * Math.PI) phase -= 2 * Math.PI;

                    outL = l;
                    outR = r;
                }

                line.write(buf, 0, frames * 4);
                sent += frames;
            }

            if (hardCut) {
                outL = 0.0;
                outR = 0.0;
            }
            lastOutL = outL;
            lastOutR = outR;
            haveLastOut = true;
        }

        @Override
        public void close() {
            try { line.drain(); } catch (Exception ignore) {}
            line.stop();
            line.close();
        }
    }

    /* ---------- Main ---------- */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame f = new JFrame("Five-Phase Encoder — v8.4");
            f.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
            f.setLayout(new BorderLayout());

            Panel panel = new Panel();
            f.add(panel, BorderLayout.CENTER);

            // ===== Top controls bar =====
            JPanel controls = new JPanel() {
                @Override
                protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setColor(new Color(0, 0, 0, 80));
                    g2.fillRoundRect(6, 6, getWidth() - 12, getHeight() - 12, 16, 16);
                    g2.dispose();
                    super.paintComponent(g);
                }
            };
            controls.setOpaque(false);
            controls.setLayout(new FlowLayout(FlowLayout.CENTER, 16, 8));

            final Color ACCENT = new Color(0xFFAA33); // orange accent

            // Breath style radios
            JLabel breathLabel = new JLabel("Breath style:");
            breathLabel.setForeground(ACCENT);
            JRadioButton bCoherent = new JRadioButton("Coherent 50/50");
            JRadioButton bRelaxed  = new JRadioButton("Relaxed 60/40");
            JRadioButton bDeep     = new JRadioButton("Deep Calm 67/33");
            ButtonGroup breathGroup = new ButtonGroup();
            breathGroup.add(bCoherent);
            breathGroup.add(bRelaxed);
            breathGroup.add(bDeep);
            bCoherent.setSelected(true);
            for (JRadioButton b : new JRadioButton[]{bCoherent, bRelaxed, bDeep}) {
                b.setFocusable(false);
                b.setForeground(Color.WHITE);
                b.setOpaque(false);
            }

            // Transition radios
            JLabel transitionLabel = new JLabel("Transition:");
            transitionLabel.setForeground(ACCENT);
            JRadioButton tHard = new JRadioButton("Hard");
            JRadioButton tSoft = new JRadioButton("Soft");
            ButtonGroup transGroup = new ButtonGroup();
            transGroup.add(tHard);
            transGroup.add(tSoft);
            tSoft.setSelected(true);
            for (JRadioButton b : new JRadioButton[]{tHard, tSoft}) {
                b.setFocusable(false);
                b.setForeground(Color.WHITE);
                b.setOpaque(false);
            }

            // Speed radios
            JLabel speedLabel = new JLabel("Speed:");
            speedLabel.setForeground(ACCENT);
            JRadioButton spIgnite = new JRadioButton("IGNITE (10s)");
            JRadioButton spBal    = new JRadioButton("BALANCE (20s)");
            JRadioButton spHarm   = new JRadioButton("HARMONY (30s)");
            JRadioButton spZen    = new JRadioButton("ZEN (60s)");
            JRadioButton spTrans  = new JRadioButton("TRANSCEND (120s)");
            ButtonGroup speedGroup = new ButtonGroup();
            speedGroup.add(spIgnite);
            speedGroup.add(spBal);
            speedGroup.add(spHarm);
            speedGroup.add(spZen);
            speedGroup.add(spTrans);
            spIgnite.setSelected(true);
            for (JRadioButton b : new JRadioButton[]{spIgnite, spBal, spHarm, spZen, spTrans}) {
                b.setFocusable(false);
                b.setForeground(Color.WHITE);
                b.setOpaque(false);
            }

            // Rotation radios
            JLabel rotLabel = new JLabel("Rotation:");
            rotLabel.setForeground(ACCENT);
            JRadioButton rotNone = new JRadioButton("No motion");
            JRadioButton rotCont = new JRadioButton("Continuous");
            JRadioButton rotKin  = new JRadioButton("Kinetic 72°");
            ButtonGroup rotGroup = new ButtonGroup();
            rotGroup.add(rotNone);
            rotGroup.add(rotCont);
            rotGroup.add(rotKin);
            rotCont.setSelected(true);
            for (JRadioButton b : new JRadioButton[]{rotNone, rotCont, rotKin}) {
                b.setFocusable(false);
                b.setForeground(Color.WHITE);
                b.setOpaque(false);
            }

            // Add to bar
            controls.add(breathLabel);
            controls.add(bCoherent);
            controls.add(bRelaxed);
            controls.add(bDeep);

            controls.add(new JSeparator(SwingConstants.VERTICAL));

            controls.add(transitionLabel);
            controls.add(tHard);
            controls.add(tSoft);

            controls.add(new JSeparator(SwingConstants.VERTICAL));

            controls.add(speedLabel);
            controls.add(spIgnite);
            controls.add(spBal);
            controls.add(spHarm);
            controls.add(spZen);
            controls.add(spTrans);

            controls.add(new JSeparator(SwingConstants.VERTICAL));

            controls.add(rotLabel);
            controls.add(rotNone);
            controls.add(rotCont);
            controls.add(rotKin);

            f.add(controls, BorderLayout.NORTH);
            // ===== End controls bar =====

            // Listeners — restart to 12 o'clock when any mode changes
            bCoherent.addActionListener(e -> panel.setBreathStyle(BreathStyle.COHERENT));
            bRelaxed.addActionListener(e -> panel.setBreathStyle(BreathStyle.RELAXED));
            bDeep.addActionListener(e -> panel.setBreathStyle(BreathStyle.DEEP_CALM));

            tHard.addActionListener(e -> panel.setTransitionMode(TransitionMode.HARD_CUT));
            tSoft.addActionListener(e -> panel.setTransitionMode(TransitionMode.SOFT));

            spIgnite.addActionListener(e -> panel.setSpeedMode(SpeedMode.IGNITE));
            spBal.addActionListener(e -> panel.setSpeedMode(SpeedMode.BALANCE));
            spHarm.addActionListener(e -> panel.setSpeedMode(SpeedMode.HARMONY));
            spZen.addActionListener(e -> panel.setSpeedMode(SpeedMode.ZEN));
            spTrans.addActionListener(e -> panel.setSpeedMode(SpeedMode.TRANSCEND));

            rotNone.addActionListener(e -> panel.setRotationMode(RotationMode.NO_MOTION));
            rotCont.addActionListener(e -> panel.setRotationMode(RotationMode.CONTINUOUS));
            rotKin.addActionListener(e -> panel.setRotationMode(RotationMode.KINETIC_STEP));

            AtomicBoolean paused = new AtomicBoolean(false);
            FullScreenHelper fs = new FullScreenHelper(f);

            f.pack();
            f.setLocationRelativeTo(null);
            f.setVisible(true);

            /* ---------- Key bindings ---------- */
            JRootPane root = f.getRootPane();
            InputMap im = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
            ActionMap am = root.getActionMap();

            Runnable toggleFS = fs::toggle;
            Runnable togglePause = () -> {
                boolean now = !paused.get();
                paused.set(now);
                panel.setPausedVisual(now);
            };
            Runnable toggleHUD = () -> {
                panel.showHud = !panel.showHud;
                controls.setVisible(panel.showHud);
                panel.repaint();
            };
            Runnable escAction = () -> {
                if (fs.isFullScreen()) fs.toggle();
                else f.dispatchEvent(new WindowEvent(f, WindowEvent.WINDOW_CLOSING));
            };

            KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
                if (e.getID() == KeyEvent.KEY_PRESSED && e.getKeyCode() == KeyEvent.VK_ENTER) {
                    int m = e.getModifiersEx();
                    boolean alt   = (m & InputEvent.ALT_DOWN_MASK) != 0;
                    boolean altGr = (m & InputEvent.ALT_GRAPH_DOWN_MASK) != 0;
                    boolean ctrlAlt = (m & InputEvent.CTRL_DOWN_MASK) != 0 &&
                            (m & InputEvent.ALT_DOWN_MASK) != 0;
                    if (alt || altGr || ctrlAlt) {
                        toggleFS.run();
                        return true;
                    }
                }
                return false;
            });

            // F11, Alt+Enter
            im.put(KeyStroke.getKeyStroke("F11"), "fs");
            am.put("fs", new AbstractAction() {
                public void actionPerformed(ActionEvent e) { toggleFS.run(); }
            });
            im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.ALT_DOWN_MASK, false),
                    "fsAltPressed");
            am.put("fsAltPressed", new AbstractAction() {
                public void actionPerformed(ActionEvent e) { toggleFS.run(); }
            });

            // Global keys
            im.put(KeyStroke.getKeyStroke("SPACE"), "pause");
            am.put("pause", new AbstractAction() {
                public void actionPerformed(ActionEvent e) { togglePause.run(); }
            });

            im.put(KeyStroke.getKeyStroke("H"), "hud");
            am.put("hud", new AbstractAction() {
                public void actionPerformed(ActionEvent e) { toggleHUD.run(); }
            });

            im.put(KeyStroke.getKeyStroke("ESCAPE"), "esc");
            am.put("esc", new AbstractAction() {
                public void actionPerformed(ActionEvent e) { escAction.run(); }
            });

            // Breath style keys: Q/W/E
            im.put(KeyStroke.getKeyStroke("Q"), "bCoherent");
            am.put("bCoherent", new AbstractAction() {
                public void actionPerformed(ActionEvent e) {
                    panel.setBreathStyle(BreathStyle.COHERENT);
                    bCoherent.setSelected(true);
                }
            });

            im.put(KeyStroke.getKeyStroke("W"), "bRelaxed");
            am.put("bRelaxed", new AbstractAction() {
                public void actionPerformed(ActionEvent e) {
                    panel.setBreathStyle(BreathStyle.RELAXED);
                    bRelaxed.setSelected(true);
                }
            });

            im.put(KeyStroke.getKeyStroke("E"), "bDeep");
            am.put("bDeep", new AbstractAction() {
                public void actionPerformed(ActionEvent e) {
                    panel.setBreathStyle(BreathStyle.DEEP_CALM);
                    bDeep.setSelected(true);
                }
            });

            // Speed keys: 1–5
            im.put(KeyStroke.getKeyStroke("1"), "spIgnite");
            am.put("spIgnite", new AbstractAction() {
                public void actionPerformed(ActionEvent e) {
                    panel.setSpeedMode(SpeedMode.IGNITE);
                    spIgnite.setSelected(true);
                }
            });

            im.put(KeyStroke.getKeyStroke("2"), "spBal");
            am.put("spBal", new AbstractAction() {
                public void actionPerformed(ActionEvent e) {
                    panel.setSpeedMode(SpeedMode.BALANCE);
                    spBal.setSelected(true);
                }
            });

            im.put(KeyStroke.getKeyStroke("3"), "spHarm");
            am.put("spHarm", new AbstractAction() {
                public void actionPerformed(ActionEvent e) {
                    panel.setSpeedMode(SpeedMode.HARMONY);
                    spHarm.setSelected(true);
                }
            });

            im.put(KeyStroke.getKeyStroke("4"), "spZen");
            am.put("spZen", new AbstractAction() {
                public void actionPerformed(ActionEvent e) {
                    panel.setSpeedMode(SpeedMode.ZEN);
                    spZen.setSelected(true);
                }
            });

            im.put(KeyStroke.getKeyStroke("5"), "spTrans");
            am.put("spTrans", new AbstractAction() {
                public void actionPerformed(ActionEvent e) {
                    panel.setSpeedMode(SpeedMode.TRANSCEND);
                    spTrans.setSelected(true);
                }
            });

            // Transition keys: T/Y
            im.put(KeyStroke.getKeyStroke("T"), "tHard");
            am.put("tHard", new AbstractAction() {
                public void actionPerformed(ActionEvent e) {
                    panel.setTransitionMode(TransitionMode.HARD_CUT);
                    tHard.setSelected(true);
                }
            });

            im.put(KeyStroke.getKeyStroke("Y"), "tSoft");
            am.put("tSoft", new AbstractAction() {
                public void actionPerformed(ActionEvent e) {
                    panel.setTransitionMode(TransitionMode.SOFT);
                    tSoft.setSelected(true);
                }
            });

            // Rotation keys: 7/8/0
            im.put(KeyStroke.getKeyStroke("7"), "rotCont");
            am.put("rotCont", new AbstractAction() {
                public void actionPerformed(ActionEvent e) {
                    panel.setRotationMode(RotationMode.CONTINUOUS);
                    rotCont.setSelected(true);
                }
            });

            im.put(KeyStroke.getKeyStroke("8"), "rotKin");
            am.put("rotKin", new AbstractAction() {
                public void actionPerformed(ActionEvent e) {
                    panel.setRotationMode(RotationMode.KINETIC_STEP);
                    rotKin.setSelected(true);
                }
            });

            im.put(KeyStroke.getKeyStroke("0"), "rotNone");
            am.put("rotNone", new AbstractAction() {
                public void actionPerformed(ActionEvent e) {
                    panel.setRotationMode(RotationMode.NO_MOTION);
                    rotNone.setSelected(true);
                }
            });

            /* ---------- Audio/visual loop (SYNCED) ---------- */
            AtomicBoolean interrupt = new AtomicBoolean(false);
            Thread loop = new Thread(() -> {
                try (TonePlayer tp = new TonePlayer()) {
                    int idx = 0;
                    boolean firstRun = true;

                    panel.resetToTop();

                    while (!interrupt.get()) {
                        // Pause handling
                        while (paused.get() && !interrupt.get()) {
                            try { Thread.sleep(20); } catch (InterruptedException ignored) {}
                        }
                        if (interrupt.get()) break;

                        if (!firstRun && panel.resetRequested.getAndSet(false)) {
                            idx = 0;
                            panel.resetToTop();
                        }

                        Phase p    = PHASES[idx];
                        Phase next = PHASES[(idx + 1) % PHASES.length];

                        final int phaseIdxFinal = idx;
                        int ms = panel.perPhaseMs();
                        double inhaleFrac = panel.inhaleFrac;
                        boolean hard = (panel.transition == TransitionMode.HARD_CUT);

                        tp.playSimple(
                                p.hz,
                                ms,
                                inhaleFrac,
                                paused,
                                interrupt,
                                hard,
                                () -> panel.setPhaseAtAudioStart(p, next.color, phaseIdxFinal)
                        );

                        if (panel.rotationMode != RotationMode.NO_MOTION) {
                            panel.currentAngleDeg = (panel.currentAngleDeg + 72.0) % 360.0;
                        }

                        idx = (idx + 1) % PHASES.length;
                        firstRun = false;
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }, "phase-loop");

            f.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    interrupt.set(true);
                }
            });

            loop.setDaemon(true);
            loop.start();
        });
    }
}
