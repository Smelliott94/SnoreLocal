package dev.snorelocal;

import java.util.*;

/** Dependency-free contract checks; run by JUnit and tools/test-core.sh. */
public final class CoreChecks {
    static class Clip { long start; int count; List<Short> audio = new ArrayList<>(); String reason; }
    static class MemorySink implements EpisodeEngine.Sink {
        List<Clip> clips = new ArrayList<>(); Clip current;
        public void open(long start) { check(current == null, "sink already open"); current = new Clip(); current.start = start; }
        public void write(short[] pcm, int offset, int count) {
            check(current != null, "sink not open");
            for (int i=offset; i<offset+count; i++) current.audio.add(pcm[i]);
            current.count += count;
        }
        public void close(String reason) { current.reason = reason; clips.add(current); current = null; }
    }
    static void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); }
    static void equal(long expected, long actual, String label) { check(expected == actual, label + ": expected " + expected + ", got " + actual); }
    static short[] sequence(int start, int count) { short[] out = new short[count]; for (int i=0;i<count;i++) out[i]=(short)(start+i); return out; }
    static void feed(EpisodeEngine engine, int count, Float score) { engine.accept(sequence((int)engine.position(),count),count,score); }
    private static List<Clip> scheduledStream(int chunkSize, long randomSeed) {
        MemorySink sink = new MemorySink();
        EpisodeEngine engine = new EpisodeEngine(50,60,100,.5f,sink);
        int[] at = {70,80,90,120,130,140,300,310,320};
        float[] scores = {1f,0f,0f,1f,0f,0f,1f,0f,0f};
        Random random = new Random(randomSeed);
        int event = 0;
        while (engine.position() < 500) {
            int boundary = event < at.length ? at[event] : 500;
            int proposed = chunkSize > 0 ? chunkSize : random.nextInt(17) + 1;
            int count = Math.min(proposed,boundary-(int)engine.position());
            Float score = engine.position()+count == boundary && event < at.length ? scores[event++] : null;
            feed(engine,count,score);
        }
        engine.finish("stop");
        return sink.clips;
    }
    private static void sameClips(List<Clip> expected, List<Clip> actual, String label) {
        equal(expected.size(),actual.size(),label+" clip count");
        for (int i=0;i<expected.size();i++) {
            Clip a=expected.get(i),b=actual.get(i);
            equal(a.start,b.start,label+" start");
            equal(a.count,b.count,label+" duration");
            check(a.reason.equals(b.reason),label+" close reason");
            check(a.audio.equals(b.audio),label+" sample continuity");
        }
    }
    public static void runAll() {
        PcmRing ring = new PcmRing(5);
        ring.add(sequence(0,3),0,3);
        check(Arrays.equals(sequence(0,3),ring.snapshot()),"partial ring order");
        ring.add(sequence(3,9),0,9);
        equal(5,ring.size(),"bounded ring");
        check(Arrays.equals(sequence(7,5),ring.snapshot()),"wrapped ring order");
        short[] latest = new short[3]; ring.latest(latest);
        check(Arrays.equals(sequence(9,3),latest),"latest window");

        MemorySink sink = new MemorySink();
        EpisodeEngine engine = new EpisodeEngine(50,60,300,.5f,sink); // 10 Hz scaled time
        feed(engine,30,0f); feed(engine,30,0f); feed(engine,10,1f);
        equal(20,sink.current.start,"five second pre-roll real start");
        feed(engine,10,0f); feed(engine,10,0f); // smooth now below stop threshold
        feed(engine,50,null); // smoothed score holds until sample 80; deadline 140
        equal(1,sink.clips.size(),"tail closes episode");
        Clip first = sink.clips.get(0);
        equal(120,first.count,"pre-roll plus trailing audio");
        equal(20,first.audio.get(0),"first retained sample");
        equal(139,first.audio.get(first.count-1),"last tail sample");
        equal(1000000L+20L*1000/10, 1000000L+first.start*1000/10,"timestamp from sample clock");
        feed(engine,10,0f); engine.finish("stop");
        equal(1,sink.clips.size(),"no recording during silence");

        sink = new MemorySink(); engine = new EpisodeEngine(50,60,300,.5f,sink);
        feed(engine,50,0f); feed(engine,10,1f); feed(engine,10,0f); feed(engine,40,null);
        feed(engine,10,1f); // renewed detection within quiet gap
        feed(engine,20,null); engine.finish("stop");
        equal(1,sink.clips.size(),"nearby detections merged");
        equal(130,sink.clips.get(0).count,"Stop commits current episode immediately");
        check(sink.clips.get(0).reason.equals("stop"),"stop reason");

        sink = new MemorySink(); engine = new EpisodeEngine(50,60,100,.5f,sink);
        for (int i=0;i<37;i++) feed(engine,10,1f);
        engine.finish("stop");
        equal(4,sink.clips.size(),"sustained episode split count");
        int expected=0;
        for (Clip clip:sink.clips) {
            equal(expected,clip.start,"split contiguous timestamp");
            check(clip.count<=100,"maximum clip duration");
            for (short sample:clip.audio) equal((short)expected++,sample,"no missing/duplicate audio at split");
        }
        equal(370,expected,"all sustained episode audio retained");

        sink = new MemorySink(); engine = new EpisodeEngine(5,6,10,.5f,sink);
        feed(engine,5,1f); feed(engine,5,1f); engine.finish("stop");
        equal(1,sink.clips.size(),"no empty clip on exact split Stop");
        sink = new MemorySink(); engine = new EpisodeEngine(5,6,10,.5f,sink);
        feed(engine,5,1f); feed(engine,5,0f); feed(engine,10,0f); engine.finish("stop");
        equal(2,sink.clips.size(),"quiet deadline across split");
        equal(6,sink.clips.get(1).count,"exact tail trim inside block");

        sink = new MemorySink(); engine = new EpisodeEngine(5,6,10,.5f,sink);
        feed(engine,3,0f); feed(engine,2,1f); engine.finish("stop");
        equal(0,sink.clips.get(0).start,"startup short pre-roll never negative");
        boolean invalid = false;
        try { feed(engine,1,Float.NaN); } catch (IllegalArgumentException e) { invalid=true; }
        check(invalid,"classifier invalid output fails explicitly");

        MemorySink low = new MemorySink(), high = new MemorySink();
        EpisodeEngine lowEngine = new EpisodeEngine(5,6,10,0f,low), highEngine = new EpisodeEngine(5,6,10,1f,high);
        feed(lowEngine,3,.4f); feed(highEngine,3,.4f);
        check(!lowEngine.isActive() && highEngine.isActive(),"sensitivity changes actual classification threshold");
        lowEngine.finish("stop"); highEngine.finish("stop");

        // A coarse input block cannot bridge a quiet deadline merely because its final score is high.
        sink = new MemorySink(); engine = new EpisodeEngine(50,60,300,.5f,sink);
        feed(engine,50,1f); feed(engine,10,0f); feed(engine,10,0f); feed(engine,130,1f);
        engine.finish("stop");
        equal(2,sink.clips.size(),"expired merge deadline starts a separate episode");
        equal(0,sink.clips.get(0).start,"old episode original start");
        equal(120,sink.clips.get(0).count,"old episode ends at exact prior deadline");
        equal(150,sink.clips.get(1).start,"new episode uses fresh pre-roll");
        equal(50,sink.clips.get(1).count,"new episode pre-roll length");
        sink = new MemorySink(); engine = new EpisodeEngine(50,60,300,.5f,sink);
        feed(engine,50,1f); feed(engine,10,0f); feed(engine,10,0f); feed(engine,50,1f);
        engine.finish("stop");
        equal(1,sink.clips.size(),"detection exactly at merge deadline is merged");
        equal(120,sink.clips.get(0).count,"equality boundary retains all audio");

        List<Clip> singleSample = scheduledStream(1,0);
        sameClips(singleSample,scheduledStream(16,0),"partial model-boundary blocks");
        for (int seed=0;seed<20;seed++)
            sameClips(singleSample,scheduledStream(0,seed),"random capture partition "+seed);

        // One accepted block may span several maximum-duration clips.
        sink = new MemorySink(); engine = new EpisodeEngine(50,1000,100,.5f,sink);
        feed(engine,50,1f); feed(engine,450,1f); engine.finish("stop");
        equal(5,sink.clips.size(),"multiple splits inside one accepted block");
        expected=0;
        for (Clip clip:sink.clips) {
            equal(expected,clip.start,"large block split timestamp");
            equal(100,clip.count,"large block duration cap");
            for (short sample:clip.audio) equal(expected++,sample,"large block split continuity");
        }
        engine.finish("stop");
        equal(5,sink.clips.size(),"repeated Stop is idempotent");

        for (float score:new float[]{Float.NaN,Float.POSITIVE_INFINITY,-.01f,1.01f}) {
            boolean rejected=false;
            MemorySink invalidSink = new MemorySink();
            EpisodeEngine invalidEngine = new EpisodeEngine(5,6,10,.5f,invalidSink);
            try { invalidEngine.accept(new short[]{12345},1,score); }
            catch (IllegalArgumentException error) { rejected=true; }
            check(rejected,"invalid classifier score rejected: "+score);
            equal(0,invalidEngine.position(),"invalid score leaves sample clock unchanged");
            feed(invalidEngine,1,1f); invalidEngine.finish("stop");
            equal(1,invalidSink.clips.get(0).count,"invalid score leaves history unchanged");
            equal(0,invalidSink.clips.get(0).audio.get(0),"invalid score audio is not retained");
        }

        sink = new MemorySink(); engine = new EpisodeEngine(5,6,10,.5f,sink);
        engine.accept(new short[0],0,1f);
        equal(0,engine.position(),"empty read does not advance clock");
        check(!engine.isActive(),"empty read cannot create episode");
        for (int count:new int[]{-1,2}) {
            boolean rejected=false;
            try { engine.accept(new short[1],count,0f); }
            catch (IllegalArgumentException error) { rejected=true; }
            check(rejected,"invalid sample count rejected");
            equal(0,engine.position(),"invalid sample count leaves clock unchanged");
        }
        for (float sensitivity:new float[]{Float.NaN,-.01f,1.01f}) {
            boolean rejected=false;
            try { new EpisodeEngine(5,6,10,sensitivity,new MemorySink()); }
            catch (IllegalArgumentException error) { rejected=true; }
            check(rejected,"invalid sensitivity rejected");
        }

        // Long overnight-like input with no recording allocations; ring must remain bounded.
        final long[] written = {0}; final long[] openCount = {0};
        EpisodeEngine.Sink counting = new EpisodeEngine.Sink() {
            public void open(long start) { openCount[0]++; }
            public void write(short[] pcm,int offset,int count) { written[0]+=count; }
            public void close(String reason) { }
        };
        engine = new EpisodeEngine(80000,96000,4800000,.5f,counting);
        short[] reusable = new short[1600];
        for (int i=0;i<288000;i++) engine.accept(reusable,reusable.length,0f); // 8h at 16kHz
        engine.finish("stop");
        equal(460800000,engine.position(),"eight hour sample clock");
        equal(0,written[0],"silence never written"); equal(0,openCount[0],"silence never opens clip");
        System.out.println("Core checks passed: ring wrap, bounded overnight history, pre-roll, timestamps, tail, merging, split continuity, partial/random read partitions, repeated Stop, thresholds, invalid scores.");
    }
    public static void main(String[] args) { runAll(); }
}
