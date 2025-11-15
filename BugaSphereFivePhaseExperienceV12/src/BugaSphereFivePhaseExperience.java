// BugaSphereFivePhaseExperience.java
// Buga Sphere Five-Phase Experience — Version 12
// Designed by Seyedrasool Sadrieh @ Mesgona — November 2025, Los Angeles
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
// • Phase name + countdown on the right of pentagon.
// • Tone (Hz + musical key) on the left of pentagon.
// • Current session settings window on the left, History window on the right.
//
// Keys:
//
// Global:
//   [SPACE] Start / Pause session
//   [H]     Toggle HUD (hides everything, including buttons; only pentagon + colors stay)
//   [F11] or [Alt+Enter] Fullscreen
//   [Esc]  Exit (or exit fullscreen first)
//
//
// Speed modes (loop length):
//   [1] IGNITE     — 10s loop ( 2s / phase)
//   [2] BALANCE    — 20s loop ( 4s / phase)
//   [3] HARMONY    — 30s loop ( 6s / phase)
//   [4] ZEN        — 60s loop (12s / phase)
//   [5] TRANSCEND  — 120s loop (24s / phase)
//
//
// Breath style (inhale/exhale):
//   [Q] Coherent   — 50/50
//   [W] Relaxed    — 60/40
//   [E] Deep Calm  — 67/33
//
//
// Transition:
//   [T] Hard cut
//   [Y] Soft fade
//
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
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import java.io.File;

public class BugaSphereFivePhaseExperience {

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

    // ---------- Lifetime totals (across all sessions) ----------
    static long lifetimeTotalMs   = 0L;
    static long lifetimeSessions  = 0L;
    static long nextSessionId     = 1L;

    // Lifetime per breath style
    static long totalCoherentMs   = 0L;
    static long totalRelaxedMs    = 0L;
    static long totalDeepCalmMs   = 0L;

    // Lifetime per speed
    static long totalIgniteMs     = 0L;
    static long totalBalanceMs    = 0L;
    static long totalHarmonyMs    = 0L;
    static long totalZenMs        = 0L;
    static long totalTranscendMs  = 0L;

    static String lastSessionInfo = "";

    // Shared date/time formatters for sessions
    private static final DateTimeFormatter FMT_DATETIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FMT_DAY =
            DateTimeFormatter.ofPattern("EEE");

    /* ---------- Data I/O helpers ---------- */

    private static Path getDataDir() {
        Path baseDir = Paths.get(System.getProperty("user.dir"));
        Path dataDir = baseDir.resolve("data");
        try {
            Files.createDirectories(dataDir);
        } catch (Exception ignored) {}
        return dataDir;
    }

    private static void loadTotals() {
        Path dataDir = getDataDir();
        Path totalsPath = dataDir.resolve("totals.csv");
        if (!Files.exists(totalsPath)) return;
        try {
            List<String> lines = Files.readAllLines(totalsPath, StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.trim().isEmpty()) continue;
                if (line.startsWith("total_sessions")) continue;
                String[] parts = line.split(",");
                if (parts.length >= 2) {
                    lifetimeSessions = Long.parseLong(parts[0].trim());
                    lifetimeTotalMs  = Long.parseLong(parts[1].trim());
                }
                if (parts.length >= 5) {
                    totalCoherentMs  = Long.parseLong(parts[2].trim());
                    totalRelaxedMs   = Long.parseLong(parts[3].trim());
                    totalDeepCalmMs  = Long.parseLong(parts[4].trim());
                }
                if (parts.length >= 10) {
                    totalIgniteMs    = Long.parseLong(parts[5].trim());
                    totalBalanceMs   = Long.parseLong(parts[6].trim());
                    totalHarmonyMs   = Long.parseLong(parts[7].trim());
                    totalZenMs       = Long.parseLong(parts[8].trim());
                    totalTranscendMs = Long.parseLong(parts[9].trim());
                }
                break;
            }
            nextSessionId = lifetimeSessions + 1;
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private static void saveTotals() {
        Path dataDir = getDataDir();
        Path totalsPath = dataDir.resolve("totals.csv");
        String header = "total_sessions,total_duration_ms,total_coherent_ms,total_relaxed_ms,total_deep_ms," +
                "total_ignite_ms,total_balance_ms,total_harmony_ms,total_zen_ms,total_transcend_ms\n";
        String line   = lifetimeSessions + "," +
                lifetimeTotalMs + "," +
                totalCoherentMs + "," +
                totalRelaxedMs + "," +
                totalDeepCalmMs + "," +
                totalIgniteMs + "," +
                totalBalanceMs + "," +
                totalHarmonyMs + "," +
                totalZenMs + "," +
                totalTranscendMs + "\n";
        try {
            Files.write(totalsPath,
                    (header + line).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    // Save LAST session's segments so they can be shown at next launch
    private static void saveSegments(long sessionId, List<Panel.Segment> segments) {
        Path dataDir = getDataDir();
        Path segPath = dataDir.resolve("last_segments.csv");
        String header = "session_id,index,duration_ms,breath,speed,transition,rotation\n";
        StringBuilder sb = new StringBuilder();
        sb.append(header);
        int index = 1;
        for (Panel.Segment seg : segments) {
            sb.append(sessionId).append(",")
                    .append(index++).append(",")
                    .append(seg.durationMs).append(",")
                    .append(seg.breath.name()).append(",")
                    .append(seg.speed.name()).append(",")
                    .append(seg.transition.name()).append(",")
                    .append(seg.rotation.name()).append("\n");
        }
        try {
            Files.write(segPath,
                    sb.toString().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    // Load last session + its segments into panel on app start
    private static void loadLastSessionFromDisk(Panel panel) {
        Path dataDir = getDataDir();
        Path sessionsPath = dataDir.resolve("sessions.csv");
        if (!Files.exists(sessionsPath)) return;

        try {
            List<String> lines = Files.readAllLines(sessionsPath, StandardCharsets.UTF_8);
            String lastLine = null;
            for (String line : lines) {
                if (line.trim().isEmpty()) continue;
                if (line.startsWith("session_id")) continue;
                lastLine = line;
            }
            if (lastLine == null) return;

            String[] parts = lastLine.split(",");
            if (parts.length < 9) return;

            long sessionId   = Long.parseLong(parts[0].trim());
            String startStr  = parts[1].trim();
            long durationMs  = Long.parseLong(parts[3].trim());
            String transitionStr = parts[7].trim();
            String rotationStr   = parts[8].trim();

            LocalDateTime startLdt = LocalDateTime.parse(startStr, FMT_DATETIME);
            String dow = startLdt.format(FMT_DAY);

            lastSessionInfo = dow + " " + startStr +
                    " (" + fmtHms(durationMs) + ") — " +
                    transitionStr + ", " + rotationStr;

            // We keep lastSessionDurationMs only for history / segments logic;
            // "Current session total time" will always start at 0 on app launch.
            panel.lastSessionDurationMs = durationMs;
            panel.sessionActive = false;

            // Load last segments
            Path segPath = dataDir.resolve("last_segments.csv");
            panel.segments.clear();
            if (Files.exists(segPath)) {
                List<String> segLines = Files.readAllLines(segPath, StandardCharsets.UTF_8);
                for (String ln : segLines) {
                    if (ln.trim().isEmpty()) continue;
                    if (ln.startsWith("session_id")) continue;
                    String[] p = ln.split(",");
                    if (p.length < 7) continue;
                    long sid = Long.parseLong(p[0].trim());
                    if (sid != sessionId) continue;

                    long dur = Long.parseLong(p[2].trim());
                    BreathStyle b     = BreathStyle.valueOf(p[3].trim());
                    SpeedMode sm      = SpeedMode.valueOf(p[4].trim());
                    TransitionMode tm = TransitionMode.valueOf(p[5].trim());
                    RotationMode rm   = RotationMode.valueOf(p[6].trim());

                    Panel.Segment seg = new Panel.Segment(0L, b, sm, tm, rm);
                    seg.durationMs = dur;
                    panel.segments.add(seg);
                }
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private static String fmtHms(long ms) {
        long s = ms / 1000;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long ss = s % 60;
        return String.format("%02d:%02d:%02d", h, m, ss);
    }

    /**
     * UPDATED: use segments so that when you change breath or speed,
     * previous time stays with the old mode and does not get moved.
     */
    private static void updateTotalsForSession(Panel panel, long durationMs) {
        lifetimeSessions++;

        long segmentsSum = 0L;

        for (Panel.Segment seg : panel.segments) {
            long d = Math.max(0L, seg.durationMs);
            if (d <= 0L) continue;
            segmentsSum += d;

            // Breath totals per segment
            switch (seg.breath) {
                case RELAXED:
                    totalRelaxedMs += d;
                    break;
                case DEEP_CALM:
                    totalDeepCalmMs += d;
                    break;
                case COHERENT:
                default:
                    totalCoherentMs += d;
                    break;
            }

            // Speed totals per segment
            switch (seg.speed) {
                case BALANCE:
                    totalBalanceMs += d;
                    break;
                case HARMONY:
                    totalHarmonyMs += d;
                    break;
                case ZEN:
                    totalZenMs += d;
                    break;
                case TRANSCEND:
                    totalTranscendMs += d;
                    break;
                case IGNITE:
                default:
                    totalIgniteMs += d;
                    break;
            }
        }

        // Lifetime total time based on segments; if something went wrong,
        // fall back to the whole session duration.
        if (segmentsSum > 0L) {
            lifetimeTotalMs += segmentsSum;
        } else {
            lifetimeTotalMs += durationMs;
        }
    }

    private static void logSession(Panel panel, long startMs, long endMs, long durationMs) {
        if (durationMs <= 0) return;
        Path dataDir = getDataDir();
        Path sessionsPath = dataDir.resolve("sessions.csv");

        String header = "session_id,start_time_local,end_time_local,duration_ms,duration_hms," +
                "breath_style,speed_mode,transition,rotation\n";

        String breathLabel;
        switch (panel.breathStyle) {
            case RELAXED:   breathLabel = "Relaxed 60/40";   break;
            case DEEP_CALM: breathLabel = "Deep Calm 67/33"; break;
            case COHERENT:
            default:        breathLabel = "Coherent 50/50";  break;
        }

        String speedLabel;
        switch (panel.speedMode) {
            case BALANCE:    speedLabel = "BALANCE (20s loop)";    break;
            case HARMONY:    speedLabel = "HARMONY (30s loop)";    break;
            case ZEN:        speedLabel = "ZEN (60s loop)";        break;
            case TRANSCEND:  speedLabel = "TRANSCEND (120s loop)"; break;
            case IGNITE:
            default:         speedLabel = "IGNITE (10s loop)";     break;
        }

        String transitionLabel = (panel.transition == TransitionMode.HARD_CUT) ? "Hard" : "Soft";

        String rotationLabel;
        switch (panel.rotationMode) {
            case NO_MOTION:    rotationLabel = "No motion";   break;
            case KINETIC_STEP: rotationLabel = "Kinetic 72°"; break;
            case CONTINUOUS:
            default:           rotationLabel = "Continuous";  break;
        }

        ZoneId zone = ZoneId.systemDefault();
        LocalDateTime startLdt = LocalDateTime.ofInstant(Instant.ofEpochMilli(startMs), zone);
        LocalDateTime endLdt   = LocalDateTime.ofInstant(Instant.ofEpochMilli(endMs),   zone);

        String startStr = startLdt.format(FMT_DATETIME);
        String endStr   = endLdt.format(FMT_DATETIME);
        String dow      = startLdt.format(FMT_DAY);

        long sessionId = nextSessionId++;

        // NEW: use segments to update totals (no time “moving” across breath changes)
        updateTotalsForSession(panel, durationMs);

        String line = String.format("%d,%s,%s,%d,%s,%s,%s,%s,%s%n",
                sessionId,
                startStr,
                endStr,
                durationMs,
                fmtHms(durationMs),
                breathLabel,
                speedLabel,
                transitionLabel,
                rotationLabel
        );

        lastSessionInfo = dow + " " + startStr +
                " (" + fmtHms(durationMs) + ") — " +
                transitionLabel + ", " + rotationLabel;

        try {
            if (!Files.exists(sessionsPath)) {
                Files.write(sessionsPath,
                        header.getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            }
            Files.write(sessionsPath,
                    line.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        // Save totals + this session's segments for next launch
        saveTotals();
        saveSegments(sessionId, panel.segments);

        System.out.println("Saved session " + sessionId + ": " + line.trim());
    }

    /* ---------- Visual panel ---------- */
    static class Panel extends JPanel {

        // Per-session mode segment
        static class Segment {
            final long startMs;
            long durationMs;
            final BreathStyle breath;
            final SpeedMode speed;
            final TransitionMode transition;
            final RotationMode rotation;

            Segment(long startMs, BreathStyle breath, SpeedMode speed,
                    TransitionMode transition, RotationMode rotation) {
                this.startMs = startMs;
                this.breath = breath;
                this.speed = speed;
                this.transition = transition;
                this.rotation = rotation;
            }
        }

        Phase cur = PHASES[0];
        int phaseIndex = 0;
        boolean showHud = true;
        boolean showHistory = true;

        BreathStyle   breathStyle   = BreathStyle.COHERENT;
        TransitionMode transition   = TransitionMode.SOFT;
        SpeedMode     speedMode     = SpeedMode.IGNITE;
        RotationMode  rotationMode  = RotationMode.CONTINUOUS;

        private Color fadeFrom = PHASES[0].color;
        private Color fadeTo   = PHASES[1].color;

        int    phaseMsCurrent   = 2000;
        volatile double inhaleFrac = 0.5;
        int    inhaleMsCurrent  = 1000;
        int    exhaleMsCurrent  = 1000;

        private final long appStartNanos = System.nanoTime();
        private long pausedAccumNanos = 0L;
        private long pausedAtNanos = 0L;
        private volatile boolean pausedVisual = true;

        double currentAngleDeg = 0.0;
        private double rotStartDeg = 0.0;
        private double rotTargetDeg = 0.0;
        private double rotationDeg = 0.0;
        private static final long ROTATE_ANIM_MS = 800;

        final AtomicBoolean resetRequested = new AtomicBoolean(false);

        private static final Color PENTA_EDGE   = new Color(0x5A5A5A);
        private static final Color NEEDLE_COLOR = new Color(0x5A5A5A);

        private long phaseStartNanos = 0L;

        boolean sessionActive = false;
        long sessionStartMs = 0L;
        long lastSessionDurationMs = 0L;   // used only for history/segments gating

        // Segments for last session (mode changes)
        final List<Segment> segments = new ArrayList<>();

        // Clickable area for "Reset data" inside History window
        private Rectangle resetDataBounds = null;

        // NEW: in-panel confirmation UI for reset
        private boolean resetConfirmVisible = false;
        private Rectangle resetYesBounds = null;
        private Rectangle resetNoBounds  = null;

        Panel() {
            setBackground(new Color(20, 20, 20));
            setDoubleBuffered(true);
            updateTiming();
        }

        void setShowHistory(boolean showHistory) {
            this.showHistory = showHistory;
            repaint();
        }

        int perPhaseMs() {
            switch (speedMode) {
                case BALANCE:    return 4000;
                case HARMONY:    return 6000;
                case ZEN:        return 12000;
                case TRANSCEND:  return 24000;
                case IGNITE:
                default:         return 2000;
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
                    inhaleFrac = 0.60;
                    break;
                case DEEP_CALM:
                    inhaleFrac = 0.67;
                    break;
                case COHERENT:
                default:
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

            fadeFrom = PHASES[0].color;
            fadeTo   = PHASES[1].color;

            phaseStartNanos = pausedVisual ? pausedAtNanos : System.nanoTime();

            if (!anim.isRunning() && !pausedVisual) anim.start();
        }

        void setPhaseAtAudioStart(Phase p, Color nextColor, int idx) {
            updateTiming();

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

            fadeFrom = p.color;
            fadeTo   = nextColor;

            if (!pausedVisual && !anim.isRunning()) anim.start();
        }

        // ---- Segment helpers ----
        private void startNewSegment(long startMs) {
            Segment seg = new Segment(startMs, breathStyle, speedMode, transition, rotationMode);
            segments.add(seg);
        }

        private void closeCurrentSegment(long nowMs) {
            if (segments.isEmpty()) return;
            Segment last = segments.get(segments.size() - 1);
            if (last.durationMs == 0) {
                last.durationMs = Math.max(0L, nowMs - last.startMs);
            }
        }

        void setBreathStyle(BreathStyle style) {
            if (breathStyle == style) return;
            if (sessionActive) {
                long now = System.currentTimeMillis();
                closeCurrentSegment(now);
                breathStyle = style;
                startNewSegment(now);
            } else {
                breathStyle = style;
            }
        }

        void setTransitionMode(TransitionMode tm) {
            if (transition == tm) return;
            if (sessionActive) {
                long now = System.currentTimeMillis();
                closeCurrentSegment(now);
                transition = tm;
                startNewSegment(now);
            } else {
                transition = tm;
            }
        }

        void setSpeedMode(SpeedMode sm) {
            if (speedMode == sm) return;
            if (sessionActive) {
                long now = System.currentTimeMillis();
                closeCurrentSegment(now);
                speedMode = sm;
                startNewSegment(now);
            } else {
                speedMode = sm;
            }
        }

        void setRotationMode(RotationMode rm) {
            if (rotationMode == rm) return;
            if (sessionActive) {
                long now = System.currentTimeMillis();
                closeCurrentSegment(now);
                rotationMode = rm;
                startNewSegment(now);
            } else {
                rotationMode = rm;
            }
        }


        long loopMillis() {
            long now = pausedVisual ? pausedAtNanos : System.nanoTime();
            long eff = now - appStartNanos - pausedAccumNanos;
            long perLoop = loopPeriodNanos();
            long mod = ((eff % perLoop) + perLoop) % perLoop;
            return mod / 1_000_000L;
        }

        /**
         * UPDATED: current session time is *only* the active session.
         * After you close and reopen the app (no session active),
         * this returns 0 — so "Current session total time" starts at 00:00:00.
         */
        long currentSessionMs() {
            if (sessionActive) {
                return Math.max(0L, System.currentTimeMillis() - sessionStartMs);
            } else {
                return 0L;
            }
        }

        long currentLifetimeMs() {
            long base = BugaSphereFivePhaseExperience.lifetimeTotalMs;
            if (sessionActive) {
                return base + currentSessionMs();
            }
            return base;
        }

        void startSessionTimer() {
            sessionActive = true;
            sessionStartMs = System.currentTimeMillis();
            lastSessionDurationMs = 0L;
            segments.clear();
            startNewSegment(sessionStartMs);
        }

        long stopSessionTimer() {
            if (!sessionActive) return 0L;
            long now = System.currentTimeMillis();
            long dur = Math.max(0L, now - sessionStartMs);
            closeCurrentSegment(now);
            lastSessionDurationMs = dur;
            sessionActive = false;
            return dur;
        }

        // Called from "Reset data" logic
        void resetAllData() {
            lifetimeSessions  = 0L;
            lifetimeTotalMs   = 0L;
            totalCoherentMs   = 0L;
            totalRelaxedMs    = 0L;
            totalDeepCalmMs   = 0L;
            totalIgniteMs     = 0L;
            totalBalanceMs    = 0L;
            totalHarmonyMs    = 0L;
            totalZenMs        = 0L;
            totalTranscendMs  = 0L;
            lastSessionInfo   = "";
            nextSessionId     = 1L;

            segments.clear();
            lastSessionDurationMs = 0L;
            sessionActive = false;

            Path dataDir = getDataDir();
            try {
                Files.deleteIfExists(dataDir.resolve("sessions.csv"));
                Files.deleteIfExists(dataDir.resolve("totals.csv"));
                Files.deleteIfExists(dataDir.resolve("last_segments.csv"));
            } catch (Exception ignored) {}

            repaint();
        }

        static String fmtMillis(long ms) {
            long s = ms / 1000, m = s / 60, h = m / 60;
            s %= 60;
            m %= 60;
            return String.format("%02d:%02d:%02d", h, m, s);
        }

        static double smooth(double t) {
            t = Math.max(0, Math.min(1, t));
            return t * t * (3 - 2 * t);
        }

        static double lerpDeg(double a, double b, double t) {
            double da = ((b - a + 540) % 360) - 180;
            return (a + da * t + 360) % 360;
        }

        static String breathLabel(BreathStyle bs) {
            switch (bs) {
                case RELAXED:   return "Relaxed 60/40";
                case DEEP_CALM: return "Deep Calm 67/33";
                case COHERENT:
                default:        return "Coherent 50/50";
            }
        }

        static String speedLabelShort(SpeedMode sm) {
            switch (sm) {
                case BALANCE:   return "BALANCE";
                case HARMONY:   return "HARMONY";
                case ZEN:       return "ZEN";
                case TRANSCEND: return "TRANSCEND";
                case IGNITE:
                default:        return "IGNITE";
            }
        }

        static String transitionLabel(TransitionMode tm) {
            return (tm == TransitionMode.HARD_CUT) ? "Hard" : "Soft";
        }

        static String rotationLabel(RotationMode rm) {
            switch (rm) {
                case NO_MOTION:    return "No motion";
                case KINETIC_STEP: return "Kinetic 72°";
                case CONTINUOUS:
                default:           return "Continuous";
            }
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

            Color phaseColor;
            if (transition == TransitionMode.SOFT) {
                int fadeLen   = Math.min(600, phaseMsCurrent / 5);
                int fadeStart = Math.max(0, phaseMsCurrent - fadeLen);

                double fadeT;
                if (phaseElapsedMs <= fadeStart) {
                    fadeT = 0.0;
                } else {
                    double raw = (phaseElapsedMs - fadeStart) / (double) Math.max(1, fadeLen);
                    fadeT = Math.max(0.0, Math.min(1.0, raw));
                    fadeT = smooth(fadeT);
                }
                phaseColor = blend(PHASES[phaseIndex].color, fadeTo, fadeT);
            } else {
                phaseColor = cur.color;
            }

            Color bg = phaseColor;

            g2.setComposite(AlphaComposite.SrcOver);
            g2.setColor(bg);
            g2.fillRect(0, 0, getWidth(), getHeight());

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

            Graphics2D s = (Graphics2D) g2.create();
            s.translate(4, 6);
            s.setColor(new Color(0, 0, 0, 70));
            s.fillPolygon(poly);
            s.dispose();

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

            drawVertexAO(g2, xs, ys, cx, cy);
            drawCornerGlows(g2, xs, ys, phaseColor);

            g2.setStroke(new BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(PENTA_EDGE);
            g2.drawPolygon(poly);

            if (rotationMode != RotationMode.NO_MOTION) {
                drawNeedle(g2, cx, cy, radius);
            }

            if (showHud) {
                Color contrast = contrast(bg);

                // Header
                g2.setColor(contrast);
                g2.setFont(getFont().deriveFont(Font.BOLD, 24f));
                g2.drawString("Five-Phase Encoder", 24, 40);

                // INHALE / EXHALE word
                String breathWordNow = inInhale ? "INHALE" : "EXHALE";
                g2.setFont(getFont().deriveFont(Font.BOLD, Math.max(40f, getWidth() * 0.06f)));
                Color overlay = (contrast == Color.BLACK)
                        ? new Color(0, 0, 0, 120)
                        : new Color(255, 255, 255, 160);
                g2.setColor(overlay);
                int bw = g2.getFontMetrics().stringWidth(breathWordNow);
                int inhaleY = Math.max(60, cy - radius - 40);
                g2.drawString(breathWordNow, (getWidth() - bw) / 2, inhaleY);

                // Tone on the left (larger, aligned with phase)
                int leftToneX = Math.max(24, cx - radius - 260);
                g2.setColor(contrast);
                g2.setFont(getFont().deriveFont(Font.BOLD, 24f));
                String noteName = hzToNoteName(cur.hz);
                String toneText = "Tone: " + (int) cur.hz + " Hz (" + noteName + ")";
                g2.drawString(toneText, leftToneX, cy + 10);

                // Phase name on right
                int rightX = cx + radius + 40;

                g2.setColor(contrast);
                g2.setFont(getFont().deriveFont(Font.BOLD, 28f));
                String phaseLabel = cur.name;
                g2.drawString(phaseLabel, rightX, cy - 10);

                // Breath countdown chip (right)
                int segmentMs = inInhale ? inhaleMsCurrent : exhaleMsCurrent;
                int segPosMs  = inInhale
                        ? (int) Math.min(segmentMs, Math.max(0, phaseElapsedMs))
                        : (int) Math.min(segmentMs, Math.max(0, phaseElapsedMs - inhaleMsCurrent));
                int remaining = (int) Math.ceil((segmentMs - segPosMs) / 1000.0);

                drawBreathCountdownChip(g2, rightX, cy + 40, remaining, inInhale, bg);

                // Left window: current session settings
                drawCurrentSessionSettings(g2, bg);

                // Right window: History
                if (showHistory) {
                    drawHistoryWindow(g2, bg);
                }

                // Footer: help only
                g2.setColor(contrast);
                g2.setFont(getFont().deriveFont(Font.PLAIN, 14f));

                String help =
                        "[F11 / Alt+Enter] Full Screen   [SPACE] Start / Pause   [H] HUD   " +
                                "[Q/W/E] Breath   [1–5] Speed   [T/Y] Transition   [7/8/0] Rotation   [Esc] Exit";

                int wFooter = g2.getFontMetrics().stringWidth(help);
                g2.drawString(help, Math.max(24, getWidth() / 2 - wFooter / 2), getHeight() - 24);
            }

            g2.dispose();
        }

        private void drawCurrentSessionSettings(Graphics2D g2, Color bg) {
            Color contrast = contrast(bg);
            int margin = 24;
            int boxW = 360;
            int boxH = 210;
            int boxRadius = 18;

            int leftX = margin;
            int settingsY = 90;

            Font base = getFont();
            Font titleFont = base.deriveFont(Font.BOLD, 16f);
            Font bold14 = base.deriveFont(Font.BOLD, 14f);
            Font plain14 = base.deriveFont(Font.PLAIN, 14f);

            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(0, 0, 0, 90));
            g2.fillRoundRect(leftX, settingsY, boxW, boxH, boxRadius, boxRadius);

            g2.setColor(contrast);
            g2.setFont(titleFont);
            g2.drawString("Current session settings", leftX + 14, settingsY + 24);

            int textX = leftX + 16;
            int textY = settingsY + 44;
            int lineStep = 18;

            g2.setFont(bold14);
            FontMetrics fmBold = g2.getFontMetrics(bold14);

            // Breath
            String breathText = breathLabel(breathStyle);
            String lbl = "Breath: ";
            g2.drawString(lbl, textX, textY);
            int lw = fmBold.stringWidth(lbl);
            g2.setFont(plain14);
            g2.drawString(breathText, textX + lw, textY);
            textY += lineStep;

            // Speed
            g2.setFont(bold14);
            String speedText;
            switch (speedMode) {
                case BALANCE:   speedText = "BALANCE (20s loop)";    break;
                case HARMONY:   speedText = "HARMONY (30s loop)";    break;
                case ZEN:       speedText = "ZEN (60s loop)";        break;
                case TRANSCEND: speedText = "TRANSCEND (120s loop)"; break;
                case IGNITE:
                default:        speedText = "IGNITE (10s loop)";     break;
            }
            lbl = "Speed: ";
            g2.drawString(lbl, textX, textY);
            lw = fmBold.stringWidth(lbl);
            g2.setFont(plain14);
            g2.drawString(speedText, textX + lw, textY);
            textY += lineStep;

            // Loop length (moved here, under speed)
            g2.setFont(bold14);
            lbl = "Loop length: ";
            g2.drawString(lbl, textX, textY);
            lw = fmBold.stringWidth(lbl);
            g2.setFont(plain14);
            String loopText = loopSeconds() + " s (5 phases)";
            g2.drawString(loopText, textX + lw, textY);
            textY += lineStep;

            // Transition
            g2.setFont(bold14);
            String transitionText = transitionLabel(transition);
            lbl = "Transition: ";
            g2.drawString(lbl, textX, textY);
            lw = fmBold.stringWidth(lbl);
            g2.setFont(plain14);
            g2.drawString(transitionText, textX + lw, textY);
            textY += lineStep;

            // Rotation
            g2.setFont(bold14);
            String rotationText = rotationLabel(rotationMode);
            lbl = "Rotation: ";
            g2.drawString(lbl, textX, textY);
            lw = fmBold.stringWidth(lbl);
            g2.setFont(plain14);
            g2.drawString(rotationText, textX + lw, textY);
            textY += lineStep;

            // Current session total time
            g2.setFont(bold14);
            lbl = "Current session total time: ";
            g2.drawString(lbl, textX, textY);
            lw = fmBold.stringWidth(lbl);
            g2.setFont(plain14);
            g2.drawString(fmtMillis(currentSessionMs()), textX + lw, textY);
        }

        private void drawHistoryWindow(Graphics2D g2, Color bg) {
            Color contrast = contrast(bg);
            int margin = 24;
            int boxW = 360;
            int boxRadius = 18;

            int historyX = getWidth() - margin - boxW;
            int historyY = 90;

            Font base = getFont();
            Font titleFont = base.deriveFont(Font.BOLD, 16f);
            Font bold14 = base.deriveFont(Font.BOLD, 14f);
            Font plain14 = base.deriveFont(Font.PLAIN, 14f);

            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int lineStep = 18;
            int contentTop = historyY + 44;
            int textY = contentTop;

            FontMetrics fmBold = g2.getFontMetrics(bold14);

            // ----- First pass: compute needed height -----
            // Total sessions + time
            textY += lineStep; // Total sessions
            textY += lineStep; // Total time

            // "Last session:" + wrapped value
            textY += lineStep; // label
            String lastText = lastSessionInfo == null ? "" : lastSessionInfo;
            g2.setFont(plain14);
            int maxLastW = boxW - 32;
            int lastLines = lastText.isEmpty()
                    ? 1
                    : wrapTextToWidth(g2, lastText, maxLastW, plain14).size();
            textY += lastLines * lineStep;

            // Last session segments
            if (!segments.isEmpty() && !sessionActive && lastSessionDurationMs > 0) {
                textY += 4;
                textY += lineStep; // "Last session segments:"
                int maxSegW = boxW - 32;
                for (Segment seg : segments) {
                    String segBase = "• " + fmtMillis(seg.durationMs) +
                            " — " + speedLabelShort(seg.speed) +
                            " — " + rotationLabel(seg.rotation) +
                            " — " + transitionLabel(seg.transition);
                    int segLineCount = wrapTextToWidth(g2, segBase, maxSegW, plain14).size();
                    textY += segLineCount * lineStep;
                }
            } else {
                textY += lineStep / 2;
            }

            textY += 4;

            // Breath totals
            textY += lineStep; // "Breath totals:"
            textY += lineStep; // Coherent
            textY += lineStep; // Relaxed
            textY += lineStep; // Deep
            textY += lineStep + 4;

            // Speed totals
            textY += lineStep; // "Speed totals:"
            textY += lineStep; // Ignite
            textY += lineStep; // Balance
            textY += lineStep; // Harmony
            textY += lineStep; // Zen
            textY += lineStep; // Transcend

            // Reserve some space at bottom for the confirmation box if visible
            int confirmBoxExtra = resetConfirmVisible ? 80 : 0;

            int contentBottom = textY + confirmBoxExtra;
            int boxH = (contentBottom - historyY) + 16;
            int maxBoxH = getHeight() - historyY - 20;
            boxH = Math.min(boxH, maxBoxH);

            // ----- Background -----
            g2.setColor(new Color(0, 0, 0, 90));
            g2.fillRoundRect(historyX, historyY, boxW, boxH, boxRadius, boxRadius);

            // ----- Header -----
            g2.setColor(contrast);
            g2.setFont(titleFont);
            String title = "History";
            g2.drawString(title, historyX + 14, historyY + 24);

            // Reset data pill inside header
            String resetLabel = "Reset data";
            FontMetrics fmTitle = g2.getFontMetrics(titleFont);
            int resetW = fmTitle.stringWidth(resetLabel) + 18;
            int resetH = 22;
            int resetX = historyX + boxW - resetW - 14;
            int resetY = historyY + 8;

            g2.setColor(new Color(0, 0, 0, 130));
            g2.fillRoundRect(resetX, resetY, resetW, resetH, 12, 12);
            g2.setColor(new Color(255, 255, 255, 180));
            g2.drawRoundRect(resetX, resetY, resetW, resetH, 12, 12);

            int resetTextX = resetX + 9;
            int resetTextY = resetY + resetH - 6;
            g2.drawString(resetLabel, resetTextX, resetTextY);

            resetDataBounds = new Rectangle(resetX, resetY, resetW, resetH);

            int textX = historyX + 16;
            textY = contentTop;

            g2.setFont(bold14);
            fmBold = g2.getFontMetrics(bold14);

            // NOTE: currentSessionMs() is still used for total time only;
            // breath breakdown comes from segments.
            long csMs = currentSessionMs();

            // Total sessions
            String lbl = "Total sessions: ";
            g2.drawString(lbl, textX, textY);
            int lw = fmBold.stringWidth(lbl);
            g2.setFont(plain14);
            g2.drawString(String.valueOf(lifetimeSessions), textX + lw, textY);
            textY += lineStep;

            // Total time (lifetime + current session)
            g2.setFont(bold14);
            lbl = "Total time: ";
            g2.drawString(lbl, textX, textY);
            lw = fmBold.stringWidth(lbl);
            g2.setFont(plain14);
            g2.drawString(fmtMillis(currentLifetimeMs()), textX + lw, textY);
            textY += lineStep;

            // Last session label + text
            g2.setFont(bold14);
            lbl = "Last session:";
            g2.drawString(lbl, textX, textY);
            textY += lineStep;

            g2.setFont(plain14);
            if (!lastText.isEmpty()) {
                maxLastW = boxW - (textX - historyX) - 20;
                List<String> lastLinesList = wrapTextToWidth(g2, lastText, maxLastW, plain14);
                for (String line : lastLinesList) {
                    g2.drawString(line, textX, textY);
                    textY += lineStep;
                }
            } else {
                textY += lineStep;
            }

            // Last session segments
            if (!segments.isEmpty() && !sessionActive && lastSessionDurationMs > 0) {
                textY += 4;
                g2.setFont(bold14);
                g2.drawString("Last session segments:", textX, textY);
                textY += lineStep;

                g2.setFont(plain14);
                int maxSegW = boxW - (textX - historyX) - 20;
                for (Segment seg : segments) {
                    String segBase = "• " + fmtMillis(seg.durationMs) +
                            " — " + speedLabelShort(seg.speed) +
                            " — " + rotationLabel(seg.rotation) +
                            " — " + transitionLabel(seg.transition);
                    List<String> segLines = wrapTextToWidth(g2, segBase, maxSegW, plain14);
                    for (String sl : segLines) {
                        if (textY > historyY + boxH - 40) break;
                        g2.drawString(sl, textX, textY);
                        textY += lineStep;
                    }
                    if (textY > historyY + boxH - 40) break;
                }
            } else {
                textY += lineStep / 2;
            }

            textY += 4;

            // ---------- Breath totals using segments (no time moving between breaths) ----------
            g2.setFont(bold14);
            g2.drawString("Breath totals:", textX, textY);
            textY += lineStep;

            long effCoherent = totalCoherentMs;
            long effRelaxed  = totalRelaxedMs;
            long effDeep     = totalDeepCalmMs;

            // Only add segments from the *current* active session here
            if (sessionActive) {
                long nowMs = System.currentTimeMillis();
                for (Segment seg : segments) {
                    long d = (seg.durationMs > 0)
                            ? seg.durationMs
                            : Math.max(0L, nowMs - seg.startMs);
                    if (d <= 0L) continue;
                    switch (seg.breath) {
                        case RELAXED:
                            effRelaxed += d;
                            break;
                        case DEEP_CALM:
                            effDeep += d;
                            break;
                        case COHERENT:
                        default:
                            effCoherent += d;
                            break;
                    }
                }
            }

            g2.setFont(bold14);
            lbl = "  Coherent: ";
            g2.drawString(lbl, textX, textY);
            lw = fmBold.stringWidth(lbl);
            g2.setFont(plain14);
            g2.drawString(fmtMillis(effCoherent), textX + lw, textY);
            textY += lineStep;

            g2.setFont(bold14);
            lbl = "  Relaxed: ";
            g2.drawString(lbl, textX, textY);
            lw = fmBold.stringWidth(lbl);
            g2.setFont(plain14);
            g2.drawString(fmtMillis(effRelaxed), textX + lw, textY);
            textY += lineStep;

            g2.setFont(bold14);
            lbl = "  Deep Calm: ";
            g2.drawString(lbl, textX, textY);
            lw = fmBold.stringWidth(lbl);
            g2.setFont(plain14);
            g2.drawString(fmtMillis(effDeep), textX + lw, textY);
            textY += lineStep + 4;

            // ---------- Speed totals using segments ----------
            g2.setFont(bold14);
            g2.drawString("Speed totals:", textX, textY);
            textY += lineStep;

            long effIgnite    = totalIgniteMs;
            long effBalance   = totalBalanceMs;
            long effHarmony   = totalHarmonyMs;
            long effZen       = totalZenMs;
            long effTranscend = totalTranscendMs;

            if (sessionActive) {
                long nowMs = System.currentTimeMillis();
                for (Segment seg : segments) {
                    long d = (seg.durationMs > 0)
                            ? seg.durationMs
                            : Math.max(0L, nowMs - seg.startMs);
                    if (d <= 0L) continue;
                    switch (seg.speed) {
                        case BALANCE:
                            effBalance += d;
                            break;
                        case HARMONY:
                            effHarmony += d;
                            break;
                        case ZEN:
                            effZen += d;
                            break;
                        case TRANSCEND:
                            effTranscend += d;
                            break;
                        case IGNITE:
                        default:
                            effIgnite += d;
                            break;
                    }
                }
            }

            g2.setFont(bold14);
            lbl = "  Ignite: ";
            g2.drawString(lbl, textX, textY);
            lw = fmBold.stringWidth(lbl);
            g2.setFont(plain14);
            g2.drawString(fmtMillis(effIgnite), textX + lw, textY);
            textY += lineStep;

            g2.setFont(bold14);
            lbl = "  Balance: ";
            g2.drawString(lbl, textX, textY);
            lw = fmBold.stringWidth(lbl);
            g2.setFont(plain14);
            g2.drawString(fmtMillis(effBalance), textX + lw, textY);
            textY += lineStep;

            g2.setFont(bold14);
            lbl = "  Harmony: ";
            g2.drawString(lbl, textX, textY);
            lw = fmBold.stringWidth(lbl);
            g2.setFont(plain14);
            g2.drawString(fmtMillis(effHarmony), textX + lw, textY);
            textY += lineStep;

            g2.setFont(bold14);
            lbl = "  Zen: ";
            g2.drawString(lbl, textX, textY);
            lw = fmBold.stringWidth(lbl);
            g2.setFont(plain14);
            g2.drawString(fmtMillis(effZen), textX + lw, textY);
            textY += lineStep;

            g2.setFont(bold14);
            lbl = "  Transcend: ";
            g2.drawString(lbl, textX, textY);
            lw = fmBold.stringWidth(lbl);
            g2.setFont(plain14);
            g2.drawString(fmtMillis(effTranscend), textX + lw, textY);
            textY += lineStep;

            // ---------- In-panel confirmation UI ----------
            resetYesBounds = null;
            resetNoBounds  = null;

            if (resetConfirmVisible) {
                int dialogW = boxW - 32;
                int dialogH = 70;
                int dialogX = historyX + 16;
                int dialogY = historyY + boxH - dialogH - 16;

                // Background
                g2.setColor(new Color(0, 0, 0, 180));
                g2.fillRoundRect(dialogX, dialogY, dialogW, dialogH, 12, 12);
                g2.setColor(new Color(255, 255, 255, 180));
                g2.drawRoundRect(dialogX, dialogY, dialogW, dialogH, 12, 12);

                // Text
                g2.setFont(bold14);
                String q = "Reset all data?";
                FontMetrics fm = g2.getFontMetrics();
                int qW = fm.stringWidth(q);
                int qX = dialogX + (dialogW - qW) / 2;
                int qY = dialogY + 24;
                g2.drawString(q, qX, qY);

                // Buttons
                int btnW = 70;
                int btnH = 24;
                int gap = 16;
                int totalBtnsW = btnW * 2 + gap;
                int btnStartX = dialogX + (dialogW - totalBtnsW) / 2;
                int btnY = dialogY + dialogH - btnH - 10;

                // YES
                resetYesBounds = new Rectangle(btnStartX, btnY, btnW, btnH);
                g2.setColor(new Color(30, 160, 120, 220));
                g2.fillRoundRect(btnStartX, btnY, btnW, btnH, 10, 10);
                g2.setColor(Color.WHITE);
                g2.drawRoundRect(btnStartX, btnY, btnW, btnH, 10, 10);
                String yesText = "Yes";
                int yesW = fm.stringWidth(yesText);
                g2.drawString(yesText,
                        btnStartX + (btnW - yesW) / 2,
                        btnY + btnH - 7);

                // NO
                int noX = btnStartX + btnW + gap;
                resetNoBounds = new Rectangle(noX, btnY, btnW, btnH);
                g2.setColor(new Color(120, 120, 120, 220));
                g2.fillRoundRect(noX, btnY, btnW, btnH, 10, 10);
                g2.setColor(Color.WHITE);
                g2.drawRoundRect(noX, btnY, btnW, btnH, 10, 10);
                String noText = "No";
                int noW = fm.stringWidth(noText);
                g2.drawString(noText,
                        noX + (btnW - noW) / 2,
                        btnY + btnH - 7);
            }
        }

        private void drawBreathCountdownChip(Graphics2D g2, int xLeft, int centerY,
                                             int remaining, boolean inInhale, Color bg) {
            int boxH = 64;
            int boxW = 160;
            int x = xLeft;
            int y = centerY - boxH / 2 + 12;

            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(0, 0, 0, 90));
            g2.fillRoundRect(x, y, boxW, boxH, 16, 16);

            Color fg = contrast(bg);
            g2.setColor(fg);

            g2.setFont(getFont().deriveFont(Font.BOLD, 16f));
            String lbl = inInhale ? "Inhale" : "Exhale";
            g2.drawString(lbl, x + 16, y + 24);

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

        static List<String> wrapTextToWidth(Graphics2D g2, String text, int maxWidth, Font font) {
            List<String> lines = new ArrayList<>();
            if (text == null || text.isEmpty()) return lines;

            FontMetrics fm = g2.getFontMetrics(font);
            String[] words = text.split("\\s+");
            StringBuilder current = new StringBuilder();

            for (String w : words) {
                if (current.length() == 0) {
                    current.append(w);
                } else {
                    String candidate = current + " " + w;
                    if (fm.stringWidth(candidate) <= maxWidth) {
                        current.append(" ").append(w);
                    } else {
                        lines.add(current.toString());
                        current = new StringBuilder(w);
                    }
                }
            }
            if (current.length() > 0) {
                lines.add(current.toString());
            }
            return lines;
        }

        // Handle mouse click for "Reset data" and confirmation buttons
        void handleClick(Point p, Component parent) {
            if (!showHud || !showHistory) return;

            // If confirmation is open, check Yes/No first
            if (resetConfirmVisible) {
                if (resetYesBounds != null && resetYesBounds.contains(p)) {
                    resetConfirmVisible = false;
                    resetAllData();
                    return;
                }
                if (resetNoBounds != null && resetNoBounds.contains(p)) {
                    resetConfirmVisible = false;
                    repaint();
                    return;
                }
            }

            // If clicking the Reset pill, show confirmation box
            if (resetDataBounds != null && resetDataBounds.contains(p)) {
                resetConfirmVisible = true;
                repaint();
            }
        }
    }

    /* ---------- Fullscreen helper ---------- */
    static class FullScreenHelper {
        private final JFrame frame;
        private Rectangle prevBounds;
        private boolean wasDecorated;
        private boolean isFullScreen = false;
        private GraphicsDevice fsDevice;

        FullScreenHelper(JFrame f) {
            this.frame = f;
        }

        boolean isFullScreen() {
            return isFullScreen;
        }

        void toggle() {
            if (isFullScreen) {
                exitFullScreen();
            } else {
                enterFullScreen();
            }
        }

        private void enterFullScreen() {
            GraphicsDevice dev = GraphicsEnvironment
                    .getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice();

            fsDevice = dev;
            prevBounds = frame.getBounds();
            wasDecorated = frame.isUndecorated();

            try {
                // Required to change decoration flags
                frame.dispose();
                frame.setUndecorated(true);
                frame.setResizable(false);

                // Show window before or while going fullscreen
                frame.setVisible(true);

                if (dev.isFullScreenSupported()) {
                    dev.setFullScreenWindow(frame);
                } else {
                    frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
                }

                isFullScreen = true;

                // Make sure it doesn't end up behind other windows
                bringToFrontAndFocus();

            } catch (Exception ex) {
                // Fallback: just maximize
                frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
                frame.setVisible(true);
                isFullScreen = true;

                bringToFrontAndFocus();
            }
        }

        private void exitFullScreen() {
            try {
                // Release fullscreen on the device if we're in it
                if (fsDevice != null && fsDevice.getFullScreenWindow() == frame) {
                    fsDevice.setFullScreenWindow(null);
                }
            } catch (Exception ignore) {
            }

            // Required to safely change undecorated flag
            frame.dispose();
            frame.setUndecorated(wasDecorated);
            frame.setResizable(true);

            if (prevBounds != null) {
                frame.setBounds(prevBounds);
            } else {
                frame.setExtendedState(JFrame.NORMAL);
            }

            frame.setVisible(true);
            isFullScreen = false;

            // Again, make sure it stays visible and focused
            bringToFrontAndFocus();
        }

        // Helper used by both enter/exit to avoid the "window vanished" feeling
        private void bringToFrontAndFocus() {
            frame.setAlwaysOnTop(true);
            frame.toFront();
            frame.requestFocus();
            frame.setAlwaysOnTop(false);
        }
    }

    /* ---------- Audio player (stereo mono-style) ---------- */
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
                        double inhaleFrac,
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

                    double env;
                    if (g < attack) {
                        env = g / (double) Math.max(1, attack);
                    } else if (g > total - release) {
                        env = (total - g) / (double) Math.max(1, release);
                    } else {
                        env = 1.0;
                    }

                    double hann = hardCut ? 1.0 : 0.5 * (1 - Math.cos(2 * Math.PI * g / Math.max(1, total - 1)));

                    double pure = Math.sin(phase);
                    double rounded = 0.85 * pure + 0.15 * Math.sin(phase * 0.5);
                    double sVal = rounded * env * hann * 0.30;

                    double l = sVal;
                    double r = sVal;

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

    /* ---------- Note name helper ---------- */
    static String hzToNoteName(double hz) {
        if (hz <= 0) return "?";
        double n = 12 * (Math.log(hz / 440.0) / Math.log(2.0));
        int midi = (int) Math.round(69 + n);
        String[] sharpNames = { "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B" };
        String name = sharpNames[(midi + 1200) % 12];

        String flat;
        switch (name) {
            case "C#": flat = "Db"; break;
            case "D#": flat = "Eb"; break;
            case "F#": flat = "Gb"; break;
            case "G#": flat = "Ab"; break;
            case "A#": flat = "Bb"; break;
            default:   flat = name; break;
        }

        int octave = (midi / 12) - 1;
        return flat + octave;
    }

    /* ---------- Main ---------- */
    public static void main(String[] args) {
        loadTotals();

        SwingUtilities.invokeLater(() -> {
            JFrame f = new JFrame("BugaSphere Five-Phase Experience Version 12");
            // -------- App Icon (v12, safe loading) --------
            try {
                java.util.List<Image> icons = new java.util.ArrayList<>();

                // Try classpath resources first: /icon16.png, /icon32.png, etc.
                int[] sizes = {16, 32, 64, 128, 256};
                for (int size : sizes) {
                    String name = "/icon" + size + ".png";
                    java.net.URL url = BugaSphereFivePhaseExperience.class.getResource(name);
                    if (url != null) {
                        icons.add(ImageIO.read(url));
                    }
                }

                // If nothing was found on the classpath, fall back to files in the working dir.
                if (icons.isEmpty()) {
                    String[] fileNames = {
                            "icon16.png",
                            "icon32.png",
                            "icon64.png",
                            "icon128.png",
                            "icon256.png"
                    };
                    for (String fn : fileNames) {
                        java.io.File fIcon = new java.io.File(fn);
                        if (fIcon.exists()) {
                            icons.add(ImageIO.read(fIcon));
                        }
                    }
                }

                if (!icons.isEmpty()) {
                    f.setIconImages(icons);
                } else {
                    System.err.println("No icon images found — running without custom app icon.");
                }
            } catch (Exception ex) {
                System.err.println("Could not load app icons, continuing without custom icon.");
            }
            // -------- End App Icon --------

            f.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
            f.setLayout(new BorderLayout());

            Panel panel = new Panel();
            f.add(panel, BorderLayout.CENTER);

            // Load last session + segments so History is ready at launch
            loadLastSessionFromDisk(panel);

            final Color ACCENT = new Color(0xFFAA33);

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
            controls.setLayout(new BorderLayout());

            JPanel leftBar = new JPanel(new FlowLayout(FlowLayout.CENTER, 16, 8));
            leftBar.setOpaque(false);

            JPanel rightBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 16, 8));
            rightBar.setOpaque(false);

            JCheckBox historyToggle = new JCheckBox("History");
            historyToggle.setSelected(true);
            historyToggle.setFocusable(false);
            historyToggle.setForeground(Color.WHITE);
            historyToggle.setOpaque(false);
            rightBar.add(historyToggle);

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

            leftBar.add(breathLabel);
            leftBar.add(bCoherent);
            leftBar.add(bRelaxed);
            leftBar.add(bDeep);

            leftBar.add(new JSeparator(SwingConstants.VERTICAL));

            leftBar.add(transitionLabel);
            leftBar.add(tHard);
            leftBar.add(tSoft);

            leftBar.add(new JSeparator(SwingConstants.VERTICAL));

            leftBar.add(speedLabel);
            leftBar.add(spIgnite);
            leftBar.add(spBal);
            leftBar.add(spHarm);
            leftBar.add(spZen);
            leftBar.add(spTrans);

            leftBar.add(new JSeparator(SwingConstants.VERTICAL));

            leftBar.add(rotLabel);
            leftBar.add(rotNone);
            leftBar.add(rotCont);
            leftBar.add(rotKin);

            controls.add(leftBar, BorderLayout.CENTER);
            controls.add(rightBar, BorderLayout.EAST);

            f.add(controls, BorderLayout.NORTH);

            historyToggle.addActionListener(e -> panel.setShowHistory(historyToggle.isSelected()));

            JPanel bottomPanel = new JPanel() {
                @Override
                protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setColor(new Color(0, 0, 0, 80));
                    g2.fillRoundRect(6, 0, getWidth() - 12, getHeight() - 4, 16, 16);
                    g2.dispose();
                    super.paintComponent(g);
                }
            };
            bottomPanel.setOpaque(false);
            bottomPanel.setLayout(new FlowLayout(FlowLayout.CENTER, 20, 6));

            JButton btnStart = new JButton("▶ / || Start");
            JButton btnStop  = new JButton("■ Stop");

            for (JButton b : new JButton[]{btnStart, btnStop}) {
                b.setFocusable(false);
            }

            bottomPanel.add(btnStart);
            bottomPanel.add(btnStop);

            f.add(bottomPanel, BorderLayout.SOUTH);

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

            AtomicBoolean paused = new AtomicBoolean(true);
            FullScreenHelper fs = new FullScreenHelper(f);

            f.pack();
            f.setLocationRelativeTo(null);
            f.setVisible(true);

            Runnable startSession = () -> {
                if (panel.sessionActive) return;

                // Ask audio loop to snap back to Origin on next phase
                panel.resetRequested.set(true);

                panel.resetToTop();
                panel.startSessionTimer();
                paused.set(false);
                panel.setPausedVisual(false);
            };

            Runnable stopSession = () -> {
                if (!panel.sessionActive) return;
                long dur = panel.stopSessionTimer();
                paused.set(true);
                panel.setPausedVisual(true);
                long startMs = panel.sessionStartMs;
                long endMs = startMs + dur;
                logSession(panel, startMs, endMs, dur);
                panel.repaint();
            };

            btnStart.addActionListener(e -> {
                if (!panel.sessionActive) {
                    startSession.run();
                } else {
                    boolean nowPaused = !paused.get();
                    paused.set(nowPaused);
                    panel.setPausedVisual(nowPaused);
                }
            });

            btnStop.addActionListener(e -> stopSession.run());

            panel.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    panel.handleClick(e.getPoint(), f);
                }
            });

            JRootPane root = f.getRootPane();
            InputMap im = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
            ActionMap am = root.getActionMap();

            Runnable toggleFS = fs::toggle;
            Runnable toggleHUD = () -> {
                panel.showHud = !panel.showHud;
                controls.setVisible(panel.showHud);
                bottomPanel.setVisible(panel.showHud);
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

            im.put(KeyStroke.getKeyStroke("F11"), "fs");
            am.put("fs", new AbstractAction() {
                public void actionPerformed(ActionEvent e) { toggleFS.run(); }
            });
            im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.ALT_DOWN_MASK, false), "fsAltPressed");
            am.put("fsAltPressed", new AbstractAction() {
                public void actionPerformed(ActionEvent e) { toggleFS.run(); }
            });

            im.put(KeyStroke.getKeyStroke("SPACE"), "toggleRunPause");
            am.put("toggleRunPause", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    if (!panel.sessionActive) {
                        startSession.run();
                    } else {
                        boolean nowPaused = !paused.get();
                        paused.set(nowPaused);
                        panel.setPausedVisual(nowPaused);
                    }
                }
            });

            im.put(KeyStroke.getKeyStroke("H"), "hud");
            am.put("hud", new AbstractAction() {
                public void actionPerformed(ActionEvent e) { toggleHUD.run(); }
            });

            im.put(KeyStroke.getKeyStroke("ESCAPE"), "esc");
            am.put("esc", new AbstractAction() {
                public void actionPerformed(ActionEvent e) { escAction.run(); }
            });

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

            AtomicBoolean interrupt = new AtomicBoolean(false);

            Thread loop = new Thread(() -> {
                try (TonePlayer tp = new TonePlayer()) {
                    int idx = 0;

                    panel.resetToTop();

                    while (!interrupt.get()) {
                        while (paused.get() && !interrupt.get()) {
                            try { Thread.sleep(20); } catch (InterruptedException ignored) {}
                        }
                        if (interrupt.get()) break;

                        if (panel.resetRequested.getAndSet(false)) {
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

                        if (interrupt.get()) break;

                        if (panel.rotationMode != RotationMode.NO_MOTION) {
                            panel.currentAngleDeg = (panel.currentAngleDeg + 72.0) % 360.0;
                        }

                        idx = (idx + 1) % PHASES.length;
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }, "phase-loop");

            f.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    if (panel.sessionActive) {
                        long dur = panel.stopSessionTimer();
                        long startMs = panel.sessionStartMs;
                        long endMs = startMs + dur;
                        logSession(panel, startMs, endMs, dur);
                    }
                    interrupt.set(true);
                }
            });

            loop.setDaemon(true);
            loop.start();
        });
    }
}
