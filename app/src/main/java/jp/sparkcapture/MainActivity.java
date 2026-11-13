package jp.sparkcapture;

import android.Manifest;
import android.app.*;
import android.os.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.*;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.provider.MediaStore;
import android.net.Uri;
import android.util.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;


public class MainActivity extends Activity {
    final Object lock = new Object();
    final ArrayDeque<Frame> ring = new ArrayDeque<>();
    final ExecutorService files = Executors.newSingleThreadExecutor();
    final Handler ui = new Handler(Looper.getMainLooper());
    HandlerThread thread; Handler cameraHandler;
    CameraDevice camera; CameraCaptureSession session; ImageReader reader;
    ImageView preview; TextView status, detail; Button trigger, resume, export, previous, next, shareLast; Spinner rate;
    SeekBar scrub;
    LinearLayout steps;
    Uri pendingShare;
    int pendingIndex;
    volatile boolean armed = false, active = false;
    ArrayList<Frame> captured = new ArrayList<>();
    long pressed, lastPreview, firstFrame;
    int rotation = 90, width, height, selectedRate = 30;
    boolean reviewing, saving;
    int selectedIndex;
    Uri lastSaved;
    MediaSession remoteSession;
    String cameraId;
    Range<Integer>[] ranges;
    boolean continuousFocus;
    static class Frame {
        final byte[] y; final long time, sensor;
        Frame(byte[] y, long time, long sensor) { this.y=y; this.time=time; this.sensor=sensor; }
    }
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = new LinearLayout(this); root.setOrientation(1); root.setPadding(20,16,20,16);
        root.setBackgroundColor(Color.rgb(15,23,35));
        TextView title = label("スパークキャプチャ", 24); root.addView(title);
        root.addView(label("直前1秒から1枚を選択 → 保存・共有",14));
        preview = new ImageView(this); preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(preview,new LinearLayout.LayoutParams(-1,0,1));
        status=label("カメラを準備中",18); root.addView(status);
        detail=label("",13); root.addView(detail);
        rate = new Spinner(this);
        rate.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"15 fps","30 fps","60 fps"}));
        rate.setSelection(1); root.addView(rate);
        rate.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(AdapterView<?> p){}
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id){
                selectedRate = new int[]{15,30,60}[pos];
                if(cameraHandler!=null && active) cameraHandler.post(()->configureRequest());
            }
        });
        scrub=new SeekBar(this); scrub.setEnabled(false); root.addView(scrub);
        scrub.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar s,int p,boolean from){if(from)showFrame(p);}
            public void onStartTrackingTouch(SeekBar s){} public void onStopTrackingTouch(SeekBar s){}
        });
        steps=new LinearLayout(this);
        previous=new Button(this);previous.setText("◀ 前のコマ");previous.setEnabled(false);
        next=new Button(this);next.setText("次のコマ ▶");next.setEnabled(false);
        steps.addView(previous,new LinearLayout.LayoutParams(0,-2,1));steps.addView(next,new LinearLayout.LayoutParams(0,-2,1));root.addView(steps);
        previous.setOnClickListener(v->selectFrame(selectedIndex-1));next.setOnClickListener(v->selectFrame(selectedIndex+1));
        trigger = new Button(this); trigger.setText("撮影 · 直前1秒を確定"); trigger.setTextSize(24);
        trigger.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(25,110,185)));
        trigger.setTextColor(Color.WHITE);trigger.setEnabled(false);
        root.addView(trigger,new LinearLayout.LayoutParams(-1,(int)(96*getResources().getDisplayMetrics().density)));
        trigger.setOnClickListener(v->capture());
        export=new Button(this);export.setText("この1枚を保存して共有");export.setTextSize(18);export.setEnabled(false);root.addView(export);
        export.setOnClickListener(v->saveSelected());
        LinearLayout row=new LinearLayout(this);
        resume=new Button(this);resume.setText("撮影を再開");resume.setEnabled(false);row.addView(resume,new LinearLayout.LayoutParams(0,-2,1));
        shareLast=new Button(this);shareLast.setText("保存した写真を共有");shareLast.setEnabled(false);row.addView(shareLast,new LinearLayout.LayoutParams(0,-2,1));root.addView(row);
        resume.setOnClickListener(v->{
            if(!captured.isEmpty())new AlertDialog.Builder(this).setTitle("残りのコマを破棄しますか？").setMessage("保存済みの写真は残ります。未保存のコマを破棄して撮影を再開します。")
                .setPositiveButton("破棄して再開",(d,w)->restartCapture()).setNegativeButton("戻る",null).show();
            else restartCapture();
        });
        String saved=getPreferences(MODE_PRIVATE).getString("lastPhoto",null);
        if(saved!=null){lastSaved=Uri.parse(saved);shareLast.setEnabled(true);}
        shareLast.setOnClickListener(v->sharePhoto(lastSaved));
        remoteSession=new MediaSession(this,"SparkCapture shutter");
        remoteSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS);
        remoteSession.setPlaybackState(new PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED,0,0)
            .setActions(PlaybackState.ACTION_PLAY|PlaybackState.ACTION_PAUSE|PlaybackState.ACTION_PLAY_PAUSE).build());
        remoteSession.setCallback(new MediaSession.Callback(){
            public boolean onMediaButtonEvent(Intent intent){KeyEvent event=intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);return event!=null && handleShutter(event);}
            public void onPlay(){hardwareCapture();} public void onPause(){hardwareCapture();}
        },ui);
        root.setOnApplyWindowInsetsListener((view,insets)->{view.setPadding(20+insets.getSystemWindowInsetLeft(),16+insets.getSystemWindowInsetTop(),20+insets.getSystemWindowInsetRight(),16+insets.getSystemWindowInsetBottom());return insets;});
        setContentView(root);updateMode();
    }
    TextView label(String s,int size){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(Color.WHITE);return t;}
    protected void onResume(){super.onResume();active=true;if(remoteSession!=null)remoteSession.setActive(true);if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)startCamera();else requestPermissions(new String[]{Manifest.permission.CAMERA},1);ui.post(()->completeSavedPhoto());}
    public void onRequestPermissionsResult(int r,String[] p,int[] g){
        super.onRequestPermissionsResult(r,p,g);
        if(r==2){if(g.length>0&&g[0]==PackageManager.PERMISSION_GRANTED)saveSelected();else status.setText("写真の保存にはストレージの許可が必要です。コマは保持しています。");return;}
        if(r==1&&g.length>0&&g[0]==PackageManager.PERMISSION_GRANTED)startCamera();
        else if(r==1)status.setText("カメラの許可が必要です。設定から許可してください。");
    }
    void startCamera(){
        if(thread!=null || !active)return;
        thread=new HandlerThread("Camera");thread.start();cameraHandler=new Handler(thread.getLooper());
        cameraHandler.post(()->{
            try{
                CameraManager manager=(CameraManager)getSystemService(CAMERA_SERVICE);
                cameraId=null;
                for(String id:manager.getCameraIdList()){
                    CameraCharacteristics c=manager.getCameraCharacteristics(id);
                    if(cameraId==null)cameraId=id;
                    if(Objects.equals(c.get(CameraCharacteristics.LENS_FACING),CameraCharacteristics.LENS_FACING_BACK)){cameraId=id;break;}
                }
                if(cameraId==null)throw new IOException("利用できるカメラがありません");
                CameraCharacteristics c=manager.getCameraCharacteristics(cameraId);
                Integer orientation=c.get(CameraCharacteristics.SENSOR_ORIENTATION);rotation=orientation==null?90:orientation;
                ranges=c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
                int[] modes=c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);continuousFocus=false;
                if(modes!=null)for(int mode:modes)if(mode==CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)continuousFocus=true;
                StreamConfigurationMap map=c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                Size[] sizes=map.getOutputSizes(ImageFormat.YUV_420_888);
                Size chosen=Arrays.stream(sizes).min(Comparator.comparingLong(s->Math.abs((long)s.getWidth()*s.getHeight()-640L*480))).get();
                width=chosen.getWidth();height=chosen.getHeight();
                reader=ImageReader.newInstance(width,height,ImageFormat.YUV_420_888,3);
                reader.setOnImageAvailableListener(r->receive(r),cameraHandler);
                manager.openCamera(cameraId,new CameraDevice.StateCallback(){
                    public void onOpened(CameraDevice d){if(!active){d.close();return;}camera=d;createSession();}
                    public void onDisconnected(CameraDevice d){d.close();camera=null;fail("カメラが切断されました。アプリを開き直してください。");}
                    public void onError(CameraDevice d,int e){d.close();camera=null;fail("カメラエラー: "+e);}
                },cameraHandler);
            }catch(Exception e){fail("起動できません: "+e.getMessage());}
        });
    }
    void createSession(){try{
        camera.createCaptureSession(Collections.singletonList(reader.getSurface()),new CameraCaptureSession.StateCallback(){
            public void onConfigured(CameraCaptureSession s){if(!active){s.close();return;}session=s;synchronized(lock){ring.clear();firstFrame=0;armed=!reviewing;}configureRequest();}
            public void onConfigureFailed(CameraCaptureSession s){fail("このカメラの撮影設定に対応していません");}
        },cameraHandler);
    }catch(Exception e){fail(e.getMessage());}}
    void configureRequest(){
        if(session==null || camera==null)return;
        try{
            CaptureRequest.Builder b=camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);b.addTarget(reader.getSurface());
            b.set(CaptureRequest.CONTROL_MODE,CaptureRequest.CONTROL_MODE_AUTO);
            Range<Integer> chosen=null;
            if(ranges!=null)for(Range<Integer> r:ranges){
                if(chosen==null || score(r,selectedRate)<score(chosen,selectedRate))chosen=r;
            }
            if(chosen!=null)b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,chosen);
            if(continuousFocus)b.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            synchronized(lock){ring.clear();firstFrame=0;}
            session.setRepeatingRequest(b.build(),null,cameraHandler);
        }catch(Exception e){fail("撮影設定エラー: "+e.getMessage());}
    }
    int score(Range<Integer> r,int target){return Math.abs(r.getUpper()-target)*100+Math.abs(r.getLower()-target);}
    void receive(ImageReader source){
        try(Image img=source.acquireLatestImage()){
            if(img==null || !active || !armed)return;
            long now=SystemClock.elapsedRealtimeNanos();
            Image.Plane plane=img.getPlanes()[0];ByteBuffer buf=plane.getBuffer();int base=buf.position();
            byte[] y=new byte[width*height];
            for(int row=0;row<height;row++)for(int col=0;col<width;col++)y[row*width+col]=buf.get(base+row*plane.getRowStride()+col*plane.getPixelStride());
            Frame f=new Frame(y,now,img.getTimestamp());
            boolean ready; int count; long span;
            synchronized(lock){
                if(!armed)return;
                if(firstFrame==0)firstFrame=now;
                ring.addLast(f);while(!ring.isEmpty()&&(now-ring.peekFirst().time>1_000_000_000L || ring.size()>120))ring.removeFirst();
                ready=now-firstFrame>=1_000_000_000L;count=ring.size();span=count>1?now-ring.peekFirst().time:0;
            }
            if(now-lastPreview>150_000_000L){
                lastPreview=now;Bitmap bitmap=bitmap(f);
                final boolean ok=ready;final int n=count;final long duration=span;
                ui.post(()->{if(!armed || !active)return;preview.setImageBitmap(bitmap);trigger.setEnabled(ok);status.setText(ok?"待機中 · 音がしたら撮影":"直前1秒を準備中…");detail.setText(String.format(Locale.JAPAN,"%d × %d · 実測 %.1f fps · %dコマ",width,height,duration>0?(n-1)*1e9/duration:0,n));});
            }
        }catch(Exception e){fail("画像取得エラー: "+e.getMessage());}
    }
    Bitmap bitmap(Frame f){
        int[] pixels=new int[f.y.length];for(int i=0;i<pixels.length;i++){int y=f.y[i]&255;pixels[i]=0xff000000|(y<<16)|(y<<8)|y;}
        Bitmap raw=Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888);
        Matrix m=new Matrix();m.postRotate(rotation);Bitmap result=Bitmap.createBitmap(raw,0,0,width,height,m,true);if(result!=raw)raw.recycle();return result;
    }
    void capture(){
        if(!active || !armed || !trigger.isEnabled() || reviewing || saving)return;
        pressed=SystemClock.elapsedRealtimeNanos();
        synchronized(lock){armed=false;captured=new ArrayList<>();for(Frame f:ring)if(f.time<=pressed&&pressed-f.time<=1_000_000_000L)captured.add(f);ring.clear();}
        reviewing=true;trigger.setEnabled(false);rate.setEnabled(false);resume.setEnabled(true);updateMode();
        if(captured.isEmpty()){status.setText("対象のコマがありません。撮影を再開してください。");return;}
        setReviewEnabled(true);selectFrame(captured.size()-1);
        status.setText("コマを選び「この1枚を保存して共有」を押してください");
    }
    void updateMode(){
        boolean selecting=reviewing && !captured.isEmpty();
        scrub.setVisibility(selecting?View.VISIBLE:View.GONE);steps.setVisibility(selecting?View.VISIBLE:View.GONE);
        export.setVisibility(selecting?View.VISIBLE:View.GONE);trigger.setVisibility(reviewing?View.GONE:View.VISIBLE);
        rate.setVisibility(reviewing?View.GONE:View.VISIBLE);
    }
    void setReviewEnabled(boolean enabled){
        scrub.setEnabled(enabled);export.setEnabled(enabled);previous.setEnabled(enabled);next.setEnabled(enabled);
    }
    void selectFrame(int i){
        if(saving || captured.isEmpty())return;
        selectedIndex=Math.max(0,Math.min(i,captured.size()-1));
        scrub.setMax(captured.size()-1);scrub.setProgress(selectedIndex);showFrame(selectedIndex);
    }
    void showFrame(int i){
        if(saving || i<0||i>=captured.size())return;
        selectedIndex=i;Frame f=captured.get(i);preview.setImageBitmap(bitmap(f));
        previous.setEnabled(i>0);next.setEnabled(i+1<captured.size());
        detail.setText(String.format(Locale.JAPAN,"%d / %d コマ · ボタンの %.0f ms前",i+1,captured.size(),(pressed-f.time)/1e6));
    }
    void restartCapture(){
        captured.clear();reviewing=false;setReviewEnabled(false);resume.setEnabled(false);rate.setEnabled(true);
        synchronized(lock){ring.clear();firstFrame=0;armed=active;}
        status.setText("直前1秒を準備中…");updateMode();
    }
    void saveSelected(){
        if(saving || captured.isEmpty() || !reviewing)return;
        if(Build.VERSION.SDK_INT<=28 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},2);return;
        }
        final Frame selected=captured.get(selectedIndex);final int index=selectedIndex;
        saving=true;setReviewEnabled(false);resume.setEnabled(false);shareLast.setEnabled(false);status.setText("選択した1枚を保存中…");
        files.execute(()->{
            Uri uri=null;Bitmap bm=null;
            try{
                String name="spark-"+new java.text.SimpleDateFormat("yyyyMMdd-HHmmss-SSS",Locale.US).format(new Date())+".jpg";
                ContentValues values=new ContentValues();values.put(MediaStore.Images.Media.DISPLAY_NAME,name);values.put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");
                if(Build.VERSION.SDK_INT>=29){values.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/SparkCapture");values.put(MediaStore.Images.Media.IS_PENDING,1);}
                else{
                    File folder=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),"SparkCapture");
                    if(!folder.isDirectory() && !folder.mkdirs())throw new IOException("写真フォルダを作成できません");
                    values.put(MediaStore.Images.Media.DATA,new File(folder,name).getAbsolutePath());
                }
                uri=getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);
                if(uri==null)throw new IOException("写真の保存先を作成できません");
                bm=bitmap(selected);
                try(OutputStream out=getContentResolver().openOutputStream(uri)){
                    if(out==null || !bm.compress(Bitmap.CompressFormat.JPEG,90,out))throw new IOException("JPEGを書き込めません");
                }
                if(Build.VERSION.SDK_INT>=29){ContentValues ready=new ContentValues();ready.put(MediaStore.Images.Media.IS_PENDING,0);getContentResolver().update(uri,ready,null,null);}
                final Uri saved=uri;
                ui.post(()->{
                    saving=false;lastSaved=saved;getPreferences(MODE_PRIVATE).edit().putString("lastPhoto",saved.toString()).apply();
                    if(isDestroyed())return;
                    captured.remove(index);resume.setEnabled(true);shareLast.setEnabled(true);
                    status.setText("1枚をPictures/SparkCaptureに保存しました");
                    pendingShare=saved;pendingIndex=index;updateMode();completeSavedPhoto();
                });
            }catch(Exception e){
                if(uri!=null)try{getContentResolver().delete(uri,null,null);}catch(Exception ignored){}
                ui.post(()->{saving=false;setReviewEnabled(true);selectFrame(index);resume.setEnabled(true);shareLast.setEnabled(lastSaved!=null);status.setText("保存失敗: "+e.getMessage()+"（コマは保持しています）");});
            }finally{if(bm!=null)bm.recycle();}
        });
    }
    void completeSavedPhoto(){
        if(pendingShare==null || !active || isFinishing() || isDestroyed())return;
        final Uri saved=pendingShare;final int index=pendingIndex;pendingShare=null;
        if(captured.isEmpty()){detail.setText("すべての選択が完了しました");sharePhoto(saved);return;}
        new AlertDialog.Builder(this).setTitle("1枚を保存しました")
            .setMessage("残りのコマから追加で保存しますか？ 選んだあとに共有メニューを開きます。")
            .setPositiveButton("追加で選ぶ",(d,w)->{setReviewEnabled(true);selectFrame(Math.min(index,captured.size()-1));sharePhoto(saved);})
            .setNegativeButton("残りを破棄",(d,w)->{captured.clear();setReviewEnabled(false);updateMode();detail.setText("保存した1枚を残し、他のコマを破棄しました");sharePhoto(saved);})
            .setCancelable(false).show();
    }
    void sharePhoto(Uri uri){
        if(uri==null)return;
        Intent send=new Intent(Intent.ACTION_SEND);send.setType("image/jpeg");send.putExtra(Intent.EXTRA_STREAM,uri);
        send.setClipData(ClipData.newRawUri("撮影した写真",uri));send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try{startActivity(Intent.createChooser(send,"写真を共有・メールに添付"));}
        catch(ActivityNotFoundException e){status.setText("共有先のアプリがありません。写真は保存済みです。");}
        catch(SecurityException e){status.setText("写真を共有できません。ギャラリーから共有してください。");}
    }
    boolean handleShutter(KeyEvent event){
        int code=event.getKeyCode();
        if(code==KeyEvent.KEYCODE_FOCUS)return true;
        if(!ShutterKeys.isShutter(code))return false;
        if(ShutterKeys.shouldTrigger(event.getAction(),event.getRepeatCount(),event.isCanceled()))hardwareCapture();
        return true;
    }
    void hardwareCapture(){if(active && armed && !reviewing && !saving && trigger.isEnabled())capture();}
    public boolean dispatchKeyEvent(KeyEvent event){return handleShutter(event)||super.dispatchKeyEvent(event);}
    protected void onDestroy(){if(remoteSession!=null)remoteSession.release();files.shutdown();super.onDestroy();}
    void fail(String message){synchronized(lock){armed=false;}ui.post(()->{status.setText(message);trigger.setEnabled(false);});}
    protected void onPause(){
        active=false;if(remoteSession!=null)remoteSession.setActive(false);synchronized(lock){armed=false;ring.clear();}trigger.setEnabled(false);
        if(cameraHandler!=null){HandlerThread old=thread;cameraHandler.post(()->{if(session!=null){session.close();session=null;}if(camera!=null){camera.close();camera=null;}if(reader!=null){reader.close();reader=null;}old.quitSafely();});try{old.join(2000);}catch(InterruptedException e){Thread.currentThread().interrupt();}thread=null;cameraHandler=null;}
        super.onPause();
    }
}
