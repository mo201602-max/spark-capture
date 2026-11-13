package jp.sparkcapture;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Versioned ZIP with portable PNG frames and relative nanosecond timestamps. */
final class ClipArchive {
    static final int MAX_FRAMES=120, MAX_IMAGE_BYTES=8*1024*1024;
    interface Source { byte[] image(int index) throws IOException; }
    interface Sink { void image(byte[] png, long relativeNs) throws IOException; }
    static void validateTimes(long[] times) throws IOException {
        if(times.length<1 || times.length>MAX_FRAMES)throw new IOException("コマ数が範囲外です");
        long previous=-1_000_000_000L;
        for(long t:times){if(t<previous || t>0)throw new IOException("時刻情報が不正です");previous=t;}
    }
    static void write(OutputStream out,long[] times,Source source) throws IOException {
        validateTimes(times);
        Properties metadata=new Properties();metadata.setProperty("format","sparkclip");metadata.setProperty("version","1");
        metadata.setProperty("count",Integer.toString(times.length));
        for(int i=0;i<times.length;i++)metadata.setProperty("time."+i,Long.toString(times[i]));
        try(ZipOutputStream zip=new ZipOutputStream(out)){
            zip.putNextEntry(new ZipEntry("manifest.properties"));metadata.store(zip,"SparkClip v1 / PNG frames / relative nanoseconds");zip.closeEntry();
            long total=0;
            for(int i=0;i<times.length;i++){
                byte[] png=source.image(i);total+=png.length;
                if(png.length==0 || png.length>MAX_IMAGE_BYTES || total>64L*1024*1024)throw new IOException("クリップが大きすぎます");
                zip.putNextEntry(new ZipEntry(name(i)));zip.write(png);zip.closeEntry();
            }
        }
    }
    static void read(InputStream in,Sink sink) throws IOException {
        try(ZipInputStream zip=new ZipInputStream(in)){
            ZipEntry first=zip.getNextEntry();
            if(first==null || !"manifest.properties".equals(first.getName()))throw new IOException("SparkClipファイルではありません");
            Properties p=new Properties();p.load(new ByteArrayInputStream(readEntry(zip,65536)));
            if(!"sparkclip".equals(p.getProperty("format")) || !"1".equals(p.getProperty("version")))throw new IOException("未対応のクリップ形式です");
            int count;
            try{count=Integer.parseInt(p.getProperty("count","0"));}catch(NumberFormatException e){throw new IOException("コマ数が不正です",e);}
            if(count<1 || count>MAX_FRAMES)throw new IOException("コマ数が範囲外です");
            long[] times=new long[count];
            try{for(int i=0;i<count;i++)times[i]=Long.parseLong(p.getProperty("time."+i,"invalid"));}catch(NumberFormatException e){throw new IOException("時刻情報が不正です",e);}
            validateTimes(times);long total=0;
            for(int i=0;i<count;i++){
                ZipEntry entry=zip.getNextEntry();
                if(entry==null || entry.isDirectory() || !name(i).equals(entry.getName()))throw new IOException("画像が不足しているか順序が不正です");
                byte[] png=readEntry(zip,MAX_IMAGE_BYTES);total+=png.length;
                if(png.length==0 || total>64L*1024*1024)throw new IOException("クリップが大きすぎます");
                sink.image(png,times[i]);
            }
            if(zip.getNextEntry()!=null)throw new IOException("未対応の追加データがあります");
        }catch(IllegalArgumentException e){throw new IOException("クリップが破損しています",e);}
    }
    static String name(int i){return String.format(Locale.US,"frames/%03d.png",i+1);}
    private static byte[] readEntry(InputStream in,int limit)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
        while((n=in.read(buffer))!=-1){if(out.size()+n>limit)throw new IOException("データが大きすぎます");out.write(buffer,0,n);}
        return out.toByteArray();
    }
}
