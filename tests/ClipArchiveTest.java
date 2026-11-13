package jp.sparkcapture;

import java.io.*;
import java.util.*;
import java.util.zip.*;

public class ClipArchiveTest {
    interface Case {void run() throws Exception;}
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    static void rejects(Case c)throws Exception{try{c.run();throw new AssertionError("invalid archive accepted");}catch(IOException expected){}}
    static byte[] archive(String metadata,String... names)throws Exception{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(bytes)){
            zip.putNextEntry(new ZipEntry("manifest.properties"));zip.write(metadata.getBytes("ISO-8859-1"));zip.closeEntry();
            for(String name:names){zip.putNextEntry(new ZipEntry(name));zip.write(new byte[]{1,2,3});zip.closeEntry();}
        }return bytes.toByteArray();
    }
    static void read(byte[] bytes)throws Exception{ClipArchive.read(new ByteArrayInputStream(bytes),(png,t)->{});}
    public static void main(String[] args)throws Exception{
        byte[][] expected={{1,4,9},{7,8},{6,5,4}};long[] times={-1_000_000_000L,-500_000_000L,-10_000_000L};
        ByteArrayOutputStream out=new ByteArrayOutputStream();ClipArchive.write(out,times,i->expected[i]);
        final int[] count={0};ClipArchive.read(new ByteArrayInputStream(out.toByteArray()),(png,t)->{int i=count[0]++;check(Arrays.equals(png,expected[i]),"image payload changed");check(t==times[i],"timing changed");});check(count[0]==3,"missing frames");
        rejects(()->ClipArchive.write(new ByteArrayOutputStream(),new long[]{},i->new byte[]{1}));
        rejects(()->ClipArchive.write(new ByteArrayOutputStream(),new long[]{-1_000_000_001L},i->new byte[]{1}));
        rejects(()->ClipArchive.write(new ByteArrayOutputStream(),new long[]{1},i->new byte[]{1}));
        rejects(()->ClipArchive.write(new ByteArrayOutputStream(),new long[]{-1,-2},i->new byte[]{1}));
        rejects(()->ClipArchive.write(new ByteArrayOutputStream(),new long[]{0},i->new byte[ClipArchive.MAX_IMAGE_BYTES+1]));
        rejects(()->read(new byte[]{1,2,3}));
        String valid="format=sparkclip\nversion=1\ncount=2\ntime.0=-100\ntime.1=0\n";
        rejects(()->read(archive(valid,"frames/001.png")));
        rejects(()->read(archive(valid,"../escape.png","frames/002.png")));
        rejects(()->read(archive(valid,"frames/002.png","frames/001.png")));
        rejects(()->read(archive(valid,"frames/001.png","frames/002.png","extra.txt")));
        rejects(()->read(archive(valid.replace("version=1","version=9"),"frames/001.png","frames/002.png")));
        rejects(()->read(archive(valid.replace("count=2","count=121"))));
        rejects(()->read(archive(valid.replace("time.1=0","time.1=bad"))));
        System.out.println("Clip round-trip, timestamps, version, bounds, missing/reordered frames and path tests passed");
    }
}
