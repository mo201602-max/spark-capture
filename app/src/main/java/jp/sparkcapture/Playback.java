package jp.sparkcapture;

final class Playback {
    static int next(int index,int count,int direction){
        if(count<=1)return 0;
        int next=index+direction;
        if(next>=count)return count-2;
        if(next<0)return 1;
        return next;
    }
    static long delay(long fromNs,long toNs,double speed){
        return Math.max(16,Math.min(1000,Math.round(Math.abs(toNs-fromNs)/1_000_000.0/speed)));
    }
}
