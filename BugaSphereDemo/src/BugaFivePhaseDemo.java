import javax.sound.sampled.*;
import javax.swing.*;
import java.awt.*;

public class BugaFivePhaseDemo {

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

    static class Panel extends JPanel {
        Phase cur = PHASES[0];
        void set(Phase p){ cur=p; repaint(); }

        @Override protected void paintComponent(Graphics g){
            super.paintComponent(g);
            g.setColor(cur.color); g.fillRect(0,0,getWidth(),getHeight());
            g.setColor((0.299*cur.color.getRed()+0.587*cur.color.getGreen()+0.114*cur.color.getBlue()>153)
                    ? Color.BLACK : Color.WHITE);
            g.setFont(getFont().deriveFont(Font.BOLD, Math.max(42f, getWidth()*0.08f)));
            String s = cur.name + "  •  " + (int)cur.hz + " Hz";
            int w = g.getFontMetrics().stringWidth(s);
            g.drawString(s, (getWidth()-w)/2, (int)(getHeight()*0.55));
        }
        @Override public Dimension getPreferredSize(){ return new Dimension(900,600); }
    }

    static class Tone {
        final float SR=44100f;
        final AudioFormat fmt=new AudioFormat(SR,16,1,true,false);
        final SourceDataLine line;
        Tone() throws LineUnavailableException {
            line=(SourceDataLine)AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, fmt));
            line.open(fmt); line.start();
        }
        void play(double hz, int ms){
            int n=(int)(ms/1000.0*SR);
            byte[] buf=new byte[n*2];
            double step=2*Math.PI*hz/SR, ph=0;
            int attack=(int)(0.05*SR), release=(int)(0.30*SR);
            for(int i=0;i<n;i++){
                double gain = i<attack? i/(double)attack :
                        i>n-release? 1-(i-(n-release))/(double)release : 1.0;
                short pcm=(short)(Math.sin(ph)*0.45*gain*Short.MAX_VALUE);
                buf[2*i]=(byte)(pcm&0xFF); buf[2*i+1]=(byte)(pcm>>>8);
                ph+=step;
            }
            line.write(buf,0,buf.length);
        }
        void close(){ line.drain(); line.stop(); line.close(); }
    }

    public static void main(String[] args) throws Exception {
        JFrame f=new JFrame("Five-Phase Encoder");
        Panel p=new Panel();
        f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        f.setContentPane(p); f.pack(); f.setLocationRelativeTo(null); f.setVisible(true);

        Tone t = new Tone();
        try {
            while (f.isDisplayable()) {
                for (Phase ph: PHASES) {
                    if (!f.isDisplayable()) break;
                    SwingUtilities.invokeAndWait(() -> p.set(ph));
                    t.play(ph.hz, ph.ms);
                }
            }
        } finally { t.close(); }
    }
}
