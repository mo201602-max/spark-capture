package jp.sparkcapture;
public class PlaybackTest {
    static void check(boolean c){if(!c)throw new AssertionError();}
    public static void main(String[] args){
        check(Playback.next(2,3,1,false)==0);check(Playback.next(2,3,1,true)==1);check(Playback.next(0,3,-1,true)==1);
        check(Playback.next(1,2,1,true)==0);check(Playback.next(0,2,-1,true)==1);check(Playback.next(0,1,1,true)==0);
        int i=0,d=1;int[] expected={1,2,1,0,1,2,1};for(int e:expected){int n=Playback.next(i,3,d,true);if(i==2)d=-1;else if(i==0)d=1;check(n==e);i=n;}
        check(Playback.delay(0,40_000_000,1)==40);check(Playback.delay(0,40_000_000,0.25)==160);check(Playback.delay(40_000_000,0,2)==20);check(Playback.delay(0,1,2)==16);
        System.out.println("Playback loop, bounce, endpoint and speed tests passed");
    }
}
