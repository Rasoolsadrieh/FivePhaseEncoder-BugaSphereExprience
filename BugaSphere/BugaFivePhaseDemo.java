import javax.sound.sampled.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

public class BugaFivePhaseDemo {
    static class Phase {
        final String name;  final Color color;
        final double freqHz;  final int durationMs;
        Phase(String n, Color c, double f, int d) {
            name = n; color = c; freqHz = f; durationMs = d;
        }
    }
    private static final Phase[] PHASES = {
            new Phase("Origin",  new Color(0xFFFFFF), 440.0, 2000),
            new Phase("Growth",  new Color(0x00FF66), 554.0, 2000),
            new Phase("Peak",    new Color(0x0066FF), 659.0, 2000),
            new Phase("Decline", new Color(0xFF3333), 587.0, 2000),
            new Phase("Renewal", new Color(0xCC33FF), 702.0, 2000)
    };

    static class Panel extends JPanel {
        private Phase current = PHASES[0];
        void setPhase(Phase p){ current=p; repaint(); }
        protected void paintComponent(Graphics g){
            super.paintComponent(g);
            g.setColor(current.color);
            g.fillRect(0,0,getWidth(),getHeight());
            g.setColor(Color.BLACK);
            g.setFont(getFont().deriveFont(Font.BOLD,48f));
            g.drawString(current.name, getWidth()/3, getHeight()/2);
        }
    }

    static class TonePlayer {
        final float rate=44100f;
        final AudioFormat fmt=new AudioFormat(rate,16,1,true,false);
        final SourceDataLine line;
        TonePlayer() throws LineUnavailableException {
            line=(SourceDataLine)AudioSystem.getLine(new DataLine.Info(SourceDataLine.class,fmt));
            line.open(fmt); line.start();
        }
        void play(double f,int ms){
            int samples=(int)(ms/1000.0*rate);
            byte[] buf=new byte[samples*2];
            double phase=0, step=2*Math.PI*f/rate;
            for(int i=0;i<samples;i++){
                short v=(short)(Math.sin(phase)*Short.MAX_VALUE*0.4);
                buf[2*i]=(byte)(v&0xFF);
                buf[2*i+1]=(byte)(v>>8);
                phase+=step;
            }
            line.write(buf,0,buf.length);
        }
    }

    public static void main(String[] args)throws Exception{
        JFrame f=new JFrame("Five-Phase Encoder");
        Panel p=new Panel(); f.add(p);
        f.setSize(800,600); f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        f.setVisible(true);
        TonePlayer tp=new TonePlayer();
        while(f.isDisplayable()){
            for(Phase ph:PHASES){
                p.setPhase(ph);
                tp.play(ph.freqHz, ph.durationMs);
            }
        }
    }
}
