package com.zyntap.app.shell;

import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

public class TouchJournalTest {
    private File dir;
    private TouchJournal journal;
    @Before public void setup() throws Exception {
        dir = Files.createTempDirectory("zyn-journal-test").toFile();
        journal = new TouchJournal(dir, "boot-A");
    }
    @After public void cleanup() throws Exception {
        if (journal != null) journal.close();
        File[] files = dir.listFiles(); if (files != null) for (File f : files) f.delete();
        dir.delete();
    }
    private TouchJournal.State state(int... ids) {
        return new TouchJournal.State(123, ids, new float[ids.length], new float[ids.length]);
    }
    private void reopen(String boot) throws Exception {
        journal.close(); journal = new TouchJournal(dir, boot);
    }
    @Test public void emptyJournal() throws Exception { assertEquals(0, journal.readCandidates().length); }
    @Test public void downSurvivesRestart() throws Exception {
        journal.prepare(null, state(0)); reopen("boot-A");
        assertArrayEquals(new int[]{0}, journal.readCandidates()[0].ids);
    }
    @Test public void pointerDownHasBothSides() throws Exception {
        journal.prepare(state(0), state(0,3)); reopen("boot-A");
        TouchJournal.State[] c = journal.readCandidates(); assertEquals(2,c.length);
        assertArrayEquals(new int[]{0,3},c[0].ids); assertArrayEquals(new int[]{0},c[1].ids);
    }
    @Test public void pointerUpKeepsBeforeAndAfter() throws Exception {
        journal.prepare(state(0,3), state(3)); reopen("boot-A");
        TouchJournal.State[] c = journal.readCandidates();
        assertArrayEquals(new int[]{3},c[0].ids); assertArrayEquals(new int[]{0,3},c[1].ids);
    }
    @Test public void finalUpKeepsPossibleStuckFinger() throws Exception {
        journal.prepare(state(7), null); reopen("boot-A");
        assertArrayEquals(new int[]{7}, journal.readCandidates()[0].ids);
    }
    @Test public void rebootNeverReplaysPreviousBoot() throws Exception {
        journal.prepare(state(0),state(0,1)); reopen("boot-B");
        assertEquals(0,journal.readCandidates().length); assertFalse(new File(dir,"touch.bin").exists());
    }
    @Test public void clearAfterSuccessfulCancel() throws Exception {
        journal.prepare(null,state(0)); journal.clear(); reopen("boot-A");
        assertEquals(0,journal.readCandidates().length);
    }
    @Test public void singletonWriterLock() throws Exception {
        try (TouchJournal unexpected = new TouchJournal(dir,"boot-A")) { fail("overlapping helper must fail"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("owns")); }
    }
    @Test public void corruptRecordNeverBecomesEvent() throws Exception {
        journal.prepare(null,state(0));
        try(RandomAccessFile f = new RandomAccessFile(new File(dir,"touch.bin"),"rw")) { f.seek(8); f.writeByte(0xff); }
        try { journal.readCandidates(); fail("CRC must reject"); } catch(IOException expected) { }
    }
    @Test public void abandonedTempDoesNotReplacePublishedState() throws Exception {
        journal.prepare(null,state(2));
        Files.write(new File(dir,"touch.tmp").toPath(),new byte[]{1,2,3}); reopen("boot-A");
        assertArrayEquals(new int[]{2},journal.readCandidates()[0].ids);
    }
    @Test public void duplicateAndInvalidIdsRejected() {
        for (int[] ids : new int[][]{{1,1},{-1},{32},new int[0]}) {
            try { state(ids); fail("invalid IDs"); } catch(IllegalArgumentException expected) { }
        }
    }
    @Test public void reorderedSetDeduplicates() throws Exception {
        journal.prepare(state(0,2),state(2,0)); assertEquals(1,journal.readCandidates().length);
    }
    @Test public void maximumPointersRoundTrip() throws Exception {
        int[] ids = new int[16]; for(int i=0;i<16;i++) ids[i]=i;
        journal.prepare(null,state(ids)); reopen("boot-A"); assertArrayEquals(ids,journal.readCandidates()[0].ids);
    }
    @Test public void coordinatesAndTimeRoundTrip() throws Exception {
        TouchJournal.State s = new TouchJournal.State(456,new int[]{5},new float[]{12.5f},new float[]{31.25f});
        journal.prepare(null,s); reopen("boot-A"); TouchJournal.State r=journal.readCandidates()[0];
        assertEquals(456,r.downTime); assertEquals(12.5f,r.x[0],0); assertEquals(31.25f,r.y[0],0);
    }
    @Test public void crashOnEitherSideOfTransitionsStrictVerifierModel() throws Exception {

        TouchJournal.State[] states={null,state(0),state(0,1),state(1),null};
        int recovered=0;
        for(int cycle=0;cycle<250;cycle++) {
            for(int i=1;i<states.length;i++) {
                for(int side=0;side<2;side++) {
                    TouchJournal.State actual=side==0?states[i-1]:states[i];
                    journal.prepare(states[i-1],states[i]); reopen("boot-A");
                    boolean matched=false;
                    for(TouchJournal.State candidate:journal.readCandidates()) {
                        if(candidate.samePointers(actual)) { matched=true; recovered++; journal.clear(); break; }
                    }
                    assertEquals(actual!=null,matched);
                }
            }
        }
        assertEquals(1500,recovered); 
    }
    @Test public void repeatedClicksReusePublishedPointerPair() throws Exception {
        journal.prepare(null,state(0));
        byte[] first = Files.readAllBytes(new File(dir,"touch.bin").toPath());
        for (int i=0;i<10000;i++) {
            TouchJournal.State next = new TouchJournal.State(1000+i,new int[]{0},new float[]{i},new float[]{i});
            journal.prepare(next,null); journal.prepare(null,next);
        }
        assertArrayEquals(first,Files.readAllBytes(new File(dir,"touch.bin").toPath()));
    }
    @Test public void clearingInvalidatesWriteCache() throws Exception {
        journal.prepare(null,state(0)); journal.clear(); journal.prepare(null,state(0));
        assertTrue(new File(dir,"touch.bin").exists()); assertEquals(1,journal.readCandidates().length);
    }
}
