package jp.sparkcapture;

import android.Manifest;
import android.app.*;
import android.os.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
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
    LinearLayout steps, playbackRow;
    Button clipSave, clipOpen, clipShare, play;
    Uri lastClip;
    Spinner speed;
    CheckBox bounce;
    boolean playing, clipDirty;
    int direction=1;
    final Runnable playbackTick=new Runnable(){public void run(){
        if(!playing || !active || saving || captured.size()<2){stopPlayback();return;}
        if(!bounce.isChecked())direction=1;
        int from=selectedIndex;
        int to=Playback.next(from,captured.size(),direction,bounce.isChecked());
        if(bounce.isChecked()){if(from==captured.size()-1)direction=-1;else if(from==0)direction=1;}
        selectFrame(to);queuePlayback();
    }};
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
        final byte[] y; final long time, sensor; final int width,height,rotation;
        Frame(byte[] y,long time,long sensor,int width,int height,int rotation){
            this.y=y;this.time=time;this.sensor=sensor;this.width=width;this.height=height;this.rotation=rotation;
        }
    }
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = new LinearLayout(this); root.setOrientation(1); root.setPadding(20,16,20,16);
        root.setBackgroundColor(Color.rgb(15,23,35));
        TextView title = label("スパークキャプチャ", 24); root.addView(title);
        LinearLayout clipActions=new LinearLayout(this);
        clipOpen=new Button(this);clipOpen.setText("開く");clipSave=new Button(this);clipSave.setText("クリップ保存");clipSave.setEnabled(false);
        clipShare=new Button(this);clipShare.setText("↗");clipShare.setContentDescription("クリップを共有");clipShare.setEnabled(false);
        clipActions.addView(clipOpen,new LinearLayout.LayoutParams(0,-2,1));clipActions.addView(clipSave,new LinearLayout.LayoutParams(0,-2,1));clipActions.addView(clipShare,new LinearLayout.LayoutParams(dp(52),-2));root.addView(clipActions);
        clipOpen.setOnClickListener(v->openClip());clipSave.setOnClickListener(v->saveClip());
        String oldClip=getPreferences(MODE_PRIVATE).getString("lastClip",null);if(oldClip!=null){lastClip=Uri.parse(oldClip);clipShare.setEnabled(true);}
        clipShare.setOnClickListener(v->{if(lastClip!=null)shareFile(lastClip,"application/octet-stream","クリップを共有");});
        preview = new ImageView(this); preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(preview,new LinearLayout.LayoutParams(-1,0,1));
        status=label("準備中",16); root.addView(status);
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
            public void onStartTrackingTouch(SeekBar s){stopPlayback();} public void onStopTrackingTouch(SeekBar s){}
        });
        steps=new LinearLayout(this);
        previous=new Button(this);previous.setText("◀");previous.setEnabled(false);
        next=new Button(this);next.setText("▶");next.setEnabled(false);
        steps.addView(previous,new LinearLayout.LayoutParams(0,-2,1));steps.addView(next,new LinearLayout.LayoutParams(0,-2,1));root.addView(steps);
        previous.setContentDescription("前のコマ");next.setContentDescription("次のコマ");
        previous.setOnClickListener(v->{stopPlayback();selectFrame(selectedIndex-1);});next.setOnClickListener(v->{stopPlayback();selectFrame(selectedIndex+1);});
        playbackRow=new LinearLayout(this);
        play=new Button(this);play.setText("再生");play.setOnClickListener(v->{if(playing)stopPlayback();else startPlayback();});
        bounce=new CheckBox(this);bounce.setText("往復");bounce.setTextColor(Color.WHITE);
        speed=new Spinner(this);speed.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"0.25×","0.5×","1×","2×"}));speed.setSelection(2);
        playbackRow.addView(play,new LinearLayout.LayoutParams(0,-2,1));playbackRow.addView(bounce);playbackRow.addView(speed,new LinearLayout.LayoutParams(0,-2,1));root.addView(playbackRow);
        trigger = new Button(this);trigger.setText("");trigger.setContentDescription("シャッター");
        GradientDrawable shutter=new GradientDrawable();shutter.setShape(GradientDrawable.OVAL);shutter.setColor(Color.WHITE);shutter.setStroke(dp(6),Color.rgb(65,145,225));
        trigger.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x6656aaff),shutter,null));trigger.setBackgroundTintList(null);trigger.setEnabled(false);trigger.setAlpha(0.35f);
        LinearLayout.LayoutParams shutterLayout=new LinearLayout.LayoutParams(dp(88),dp(88));shutterLayout.gravity=Gravity.CENTER_HORIZONTAL;shutterLayout.topMargin=dp(8);shutterLayout.bottomMargin=dp(8);
        root.addView(trigger,shutterLayout);
        trigger.setOnClickListener(v->capture());
        export=new Button(this);export.setText("1枚を保存・共有");export.setTextSize(18);export.setEnabled(false);root.addView(export);
        export.setOnClickListener(v->saveSelected());
        LinearLayout row=new LinearLayout(this);
        resume=new Button(this);resume.setText("カメラへ");resume.setEnabled(false);row.addView(resume,new LinearLayout.LayoutParams(0,-2,1));
        shareLast=new Button(this);shareLast.setText("写真を共有");shareLast.setEnabled(false);row.addView(shareLast,new LinearLayout.LayoutParams(0,-2,1));root.addView(row);
        resume.setOnClickListener(v->{
            if(!captured.isEmpty() && clipDirty)new AlertDialog.Builder(this).setTitle("コマを破棄しますか？")
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
    int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
    TextView label(String s,int size){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(Color.WHITE);return t;}
    protected void onResume(){super.onResume();active=true;if(remoteSession!=null)remoteSession.setActive(true);if(!reviewing && !saving){if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)startCamera();else requestPermissions(new String[]{Manifest.permission.CAMERA},1);}ui.post(()->completeSavedPhoto());}
    public void onRequestPermissionsResult(int r,String[] p,int[] g){
        super.onRequestPermissionsResult(r,p,g);
        if(r==2){if(g.length>0&&g[0]==PackageManager.PERMISSION_GRANTED)saveSelected();else status.setText("写真の保存にはストレージの許可が必要です。コマは保持しています。");return;}
        if(r==1&&g.length>0&&g[0]==PackageManager.PERMISSION_GRANTED)startCamera();
        else if(r==1)status.setText("カメラの許可が必要です。設定から許可してください。");
    }
    void startCamera(){
        if(thread!=null || !active || reviewing || saving)return;
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
            public void onConfigured(CameraCaptureSession s){if(!active){s.close();return;}session=s;synchronized(lock){ring.clear();firstFrame=0;armed=!reviewing && !saving;}configureRequest();}
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
            Frame f=new Frame(y,now,img.getTimestamp(),width,height,rotation);
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
                ui.post(()->{if(!armed || !active)return;preview.setImageBitmap(bitmap);trigger.setEnabled(ok);trigger.setAlpha(ok?1f:0.35f);status.setText(ok?"撮影できます":"準備中");detail.setText(String.format(Locale.JAPAN,"%d × %d · %.1f fps · %dコマ",width,height,duration>0?(n-1)*1e9/duration:0,n));});
            }
        }catch(Exception e){fail("画像取得エラー: "+e.getMessage());}
    }
    Bitmap bitmap(Frame f){
        int[] pixels=new int[f.y.length];for(int i=0;i<pixels.length;i++){int y=f.y[i]&255;pixels[i]=0xff000000|(y<<16)|(y<<8)|y;}
        Bitmap raw=Bitmap.createBitmap(pixels,f.width,f.height,Bitmap.Config.ARGB_8888);
        Matrix m=new Matrix();m.postRotate(f.rotation);Bitmap result=Bitmap.createBitmap(raw,0,0,f.width,f.height,m,true);if(result!=raw)raw.recycle();return result;
    }
    void capture(){
        if(!active || !armed || !trigger.isEnabled() || reviewing || saving)return;
        pressed=SystemClock.elapsedRealtimeNanos();
        synchronized(lock){armed=false;captured=new ArrayList<>();for(Frame f:ring)if(f.time<=pressed&&pressed-f.time<=1_000_000_000L)captured.add(f);ring.clear();}
        reviewing=true;clipDirty=true;trigger.setEnabled(false);rate.setEnabled(false);resume.setEnabled(true);updateMode();
        if(captured.isEmpty()){status.setText("対象のコマがありません。撮影を再開してください。");return;}
        setReviewEnabled(true);selectFrame(captured.size()-1);
        status.setText("コマを選択");
    }
    void updateMode(){
        boolean selecting=reviewing && !captured.isEmpty();
        scrub.setVisibility(selecting?View.VISIBLE:View.GONE);steps.setVisibility(selecting?View.VISIBLE:View.GONE);
        export.setVisibility(selecting?View.VISIBLE:View.GONE);trigger.setVisibility(reviewing?View.GONE:View.VISIBLE);
        rate.setVisibility(reviewing?View.GONE:View.VISIBLE);
        playbackRow.setVisibility(selecting?View.VISIBLE:View.GONE);
        clipSave.setEnabled(selecting && !saving);clipOpen.setEnabled(!saving);clipShare.setEnabled(lastClip!=null && !saving);play.setEnabled(selecting && !saving && captured.size()>1);
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
        detail.setText(String.format(Locale.JAPAN,"%d / %d · %.0f ms前",i+1,captured.size(),(pressed-f.time)/1e6));
    }
    void restartCapture(){
        stopPlayback();captured.clear();clipDirty=false;reviewing=false;setReviewEnabled(false);resume.setEnabled(false);rate.setEnabled(true);
        synchronized(lock){ring.clear();firstFrame=0;armed=active;}
        status.setText("準備中");updateMode();if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)startCamera();else requestPermissions(new String[]{Manifest.permission.CAMERA},1);
    }
    void saveSelected(){
        if(saving || captured.isEmpty() || !reviewing)return;
        if(Build.VERSION.SDK_INT<=28 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},2);return;
        }
        stopPlayback();
        final Frame selected=captured.get(selectedIndex);final int index=selectedIndex;
        saving=true;setReviewEnabled(false);resume.setEnabled(false);shareLast.setEnabled(false);status.setText("保存中…");updateMode();
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
                    // Keep the full sequence until the user chooses to discard it.resume.setEnabled(true);shareLast.setEnabled(true);
                    status.setText("保存しました");
                    pendingShare=saved;pendingIndex=index;updateMode();completeSavedPhoto();
                });
            }catch(Exception e){
                if(uri!=null)try{getContentResolver().delete(uri,null,null);}catch(Exception ignored){}
                ui.post(()->{saving=false;setReviewEnabled(true);selectFrame(index);resume.setEnabled(true);shareLast.setEnabled(lastSaved!=null);status.setText("保存失敗: "+e.getMessage());updateMode();});
            }finally{if(bm!=null)bm.recycle();}
        });
    }
    void completeSavedPhoto(){
        if(pendingShare==null || !active || isFinishing() || isDestroyed())return;
        final Uri saved=pendingShare;final int index=pendingIndex;pendingShare=null;
        if(captured.isEmpty()){detail.setText("すべての選択が完了しました");sharePhoto(saved);return;}
        new AlertDialog.Builder(this).setTitle("1枚を保存しました")

            .setPositiveButton("追加で選ぶ",(d,w)->{setReviewEnabled(true);selectFrame(Math.min(index,captured.size()-1));sharePhoto(saved);})
            .setNegativeButton("残りを破棄",(d,w)->{stopPlayback();captured.clear();clipDirty=false;setReviewEnabled(false);updateMode();detail.setText("");sharePhoto(saved);})
            .setCancelable(false).show();
    }
    void sharePhoto(Uri uri){shareFile(uri,"image/jpeg","写真を共有");}
    void shareFile(Uri uri,String mime,String title){
        if(uri==null)return;
        Intent send=new Intent(Intent.ACTION_SEND);send.setType(mime);send.putExtra(Intent.EXTRA_STREAM,uri);
        send.setClipData(ClipData.newRawUri("撮影した写真",uri));send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try{startActivity(Intent.createChooser(send,title));}
        catch(ActivityNotFoundException e){status.setText("共有先のアプリがありません。");}
        catch(SecurityException e){status.setText("共有できません。保存先から共有してください。");}
    }
    void startPlayback(){
        if(captured.size()<2 || saving)return;
        direction=bounce.isChecked() && selectedIndex==captured.size()-1?-1:1;
        if(!bounce.isChecked() && selectedIndex==captured.size()-1)selectFrame(0);
        playing=true;play.setText("停止");queuePlayback();
    }
    void queuePlayback(){
        if(!playing || captured.size()<2)return;
        if(!bounce.isChecked())direction=1;
        int from=selectedIndex,to=Playback.next(from,captured.size(),direction,bounce.isChecked());
        if(!bounce.isChecked() && from==captured.size()-1 && to==0){from=0;to=1;}
        long delay=Playback.delay(captured.get(from).time,captured.get(to).time,new double[]{0.25,0.5,1,2}[speed.getSelectedItemPosition()]);
        ui.postDelayed(playbackTick,delay);
    }
    void stopPlayback(){playing=false;ui.removeCallbacks(playbackTick);if(play!=null)play.setText("再生");}
    void saveClip(){
        if(captured.isEmpty() || saving)return;stopPlayback();
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.setType("application/octet-stream");intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_TITLE,"clip-"+new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US).format(new Date())+".sparkclip");
        startActivityForResult(intent,20);
    }
    void openClip(){
        if(saving)return;stopPlayback();
        if(clipDirty && !captured.isEmpty())new AlertDialog.Builder(this).setTitle("今のコマを破棄して開きますか？")
            .setPositiveButton("開く",(d,w)->chooseClip()).setNegativeButton("戻る",null).show();
        else chooseClip();
    }
    void chooseClip(){
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.setType("*/*");intent.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(intent,21);
    }
    protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(result!=RESULT_OK || data==null || data.getData()==null)return;
        if(request!=20 && request!=21)return;
        int grants=data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if(grants!=0)try{getContentResolver().takePersistableUriPermission(data.getData(),grants);}catch(SecurityException ignored){}
        if(request==20)writeClip(data.getData());else readClip(data.getData());
    }
    void rememberClip(Uri uri){lastClip=uri;getPreferences(MODE_PRIVATE).edit().putString("lastClip",uri.toString()).apply();}
    void setBusy(boolean busy){
        saving=busy;setReviewEnabled(!busy && !captured.isEmpty());resume.setEnabled(!busy && reviewing);
        shareLast.setEnabled(!busy && lastSaved!=null);updateMode();
    }
    void writeClip(Uri destination){
        final ArrayList<Frame> frames=new ArrayList<>(captured);final long triggerTime=pressed;
        if(frames.isEmpty())return;setBusy(true);status.setText("保存中…");
        files.execute(()->{
            try{
                long[] times=new long[frames.size()];for(int i=0;i<times.length;i++)times[i]=frames.get(i).time-triggerTime;
                try(OutputStream out=getContentResolver().openOutputStream(destination,"wt")){if(out==null)throw new IOException("保存先を開けません");
                ClipArchive.write(out,times,i->{Bitmap bm=bitmap(frames.get(i));try{ByteArrayOutputStream bytes=new ByteArrayOutputStream();if(!bm.compress(Bitmap.CompressFormat.PNG,100,bytes))throw new IOException("画像の保存失敗");return bytes.toByteArray();}finally{bm.recycle();}});}
                ui.post(()->{if(isDestroyed())return;clipDirty=false;rememberClip(destination);setBusy(false);status.setText("クリップを保存しました");});
            }catch(Exception e){ui.post(()->{if(isDestroyed())return;setBusy(false);status.setText("保存失敗: "+e.getMessage());});}
        });
    }
    void readClip(Uri source){
        setBusy(true);synchronized(lock){armed=false;ring.clear();}trigger.setEnabled(false);status.setText("読み込み中…");
        files.execute(()->{
            try{
                ArrayList<Frame> loaded=new ArrayList<>();final long[] pixels={0};
                try(InputStream in=getContentResolver().openInputStream(source)){if(in==null)throw new IOException("ファイルを開けません");
                ClipArchive.read(in,(png,time)->{
                    BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(png,0,png.length,bounds);
                    long count=(long)bounds.outWidth*bounds.outHeight;pixels[0]+=count;
                    if(bounds.outWidth<1 || bounds.outHeight<1 || count>2_000_000 || pixels[0]>40_000_000)throw new IOException("画像サイズが範囲外です");
                    Bitmap bm=BitmapFactory.decodeByteArray(png,0,png.length);if(bm==null)throw new IOException("画像が破損しています");
                    try{int w=bm.getWidth(),h=bm.getHeight();int[] rgb=new int[w*h];bm.getPixels(rgb,0,w,0,0,w,h);byte[] y=new byte[rgb.length];for(int j=0;j<rgb.length;j++)y[j]=(byte)(rgb[j]&255);loaded.add(new Frame(y,time,0,w,h,0));}finally{bm.recycle();}
                });}
                ui.post(()->{if(isDestroyed())return;captured=loaded;pressed=0;reviewing=true;clipDirty=false;rememberClip(source);setBusy(false);selectFrame(0);status.setText("クリップ");});
            }catch(Exception e){ui.post(()->{if(isDestroyed())return;setBusy(false);if(!reviewing){synchronized(lock){firstFrame=0;armed=active;}startCamera();}status.setText("読み込み失敗: "+e.getMessage());});}
        });
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
        stopPlayback();active=false;if(remoteSession!=null)remoteSession.setActive(false);synchronized(lock){armed=false;ring.clear();}trigger.setEnabled(false);
        if(cameraHandler!=null){HandlerThread old=thread;cameraHandler.post(()->{if(session!=null){session.close();session=null;}if(camera!=null){camera.close();camera=null;}if(reader!=null){reader.close();reader=null;}old.quitSafely();});try{old.join(2000);}catch(InterruptedException e){Thread.currentThread().interrupt();}thread=null;cameraHandler=null;}
        super.onPause();
    }
}
