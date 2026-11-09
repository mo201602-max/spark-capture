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
import android.net.Uri;
import android.util.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

public class MainActivity extends Activity {
    final Object lock = new Object();
    final ArrayDeque<Frame> ring = new ArrayDeque<>();
    final ExecutorService files = Executors.newSingleThreadExecutor();
    final Handler ui = new Handler(Looper.getMainLooper());
    HandlerThread thread; Handler cameraHandler;
    CameraDevice camera; CameraCaptureSession session; ImageReader reader;
    ImageView preview; TextView status, detail; Button trigger, resume, export; Spinner rate;
    SeekBar scrub;
    volatile boolean armed = false, active = false;
    ArrayList<Frame> captured = new ArrayList<>();
    long pressed, lastPreview, firstFrame;
    int rotation = 90, width, height, selectedRate = 30;
    File archive;
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
        root.addView(label("ボタンを押す直前1秒を保存 · 白黒",14));
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
        trigger = new Button(this); trigger.setText("直前1秒を保存"); trigger.setEnabled(false); root.addView(trigger);
        trigger.setOnClickListener(v->capture());
        LinearLayout row=new LinearLayout(this);
        resume=new Button(this);resume.setText("撮影を再開");resume.setEnabled(false);row.addView(resume,new LinearLayout.LayoutParams(0,-2,1));
        export=new Button(this);export.setText("ZIPを書き出す");export.setEnabled(false);row.addView(export,new LinearLayout.LayoutParams(0,-2,1));root.addView(row);
        resume.setOnClickListener(v->{captured.clear(); archive=null; synchronized(lock){ring.clear(); firstFrame=0; armed=true;} scrub.setEnabled(false);resume.setEnabled(false);export.setEnabled(false);rate.setEnabled(true);});
        export.setOnClickListener(v->{Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.setType("application/zip");i.addCategory(Intent.CATEGORY_OPENABLE);i.putExtra(Intent.EXTRA_TITLE,archive.getName());startActivityForResult(i,10);});
        Button history=new Button(this);history.setText("保存済みZIPを選ぶ");root.addView(history);
        history.setOnClickListener(v->{
            File folder=new File(getFilesDir(),"captures");File[] saved=folder.listFiles((dir,name)->name.endsWith(".zip"));
            if(saved==null||saved.length==0){new AlertDialog.Builder(this).setMessage("保存済みの撮影はありません").setPositiveButton("OK",null).show();return;}
            Arrays.sort(saved,(a,b)->b.getName().compareTo(a.getName()));String[] names=new String[saved.length];for(int i=0;i<saved.length;i++)names[i]=saved[i].getName();
            new AlertDialog.Builder(this).setTitle("書き出す撮影を選択").setItems(names,(d,n)->{archive=saved[n];Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.setType("application/zip");i.addCategory(Intent.CATEGORY_OPENABLE);i.putExtra(Intent.EXTRA_TITLE,archive.getName());startActivityForResult(i,10);}).show();
        });
        root.setOnApplyWindowInsetsListener((view,insets)->{view.setPadding(20+insets.getSystemWindowInsetLeft(),16+insets.getSystemWindowInsetTop(),20+insets.getSystemWindowInsetRight(),16+insets.getSystemWindowInsetBottom());return insets;});
        setContentView(root);
    }
    TextView label(String s,int size){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(Color.WHITE);return t;}
    protected void onResume(){super.onResume();active=true;if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)startCamera();else requestPermissions(new String[]{Manifest.permission.CAMERA},1);}
    public void onRequestPermissionsResult(int r,String[] p,int[] g){super.onRequestPermissionsResult(r,p,g);if(r==1&&g.length>0&&g[0]==PackageManager.PERMISSION_GRANTED)startCamera();else status.setText("カメラの許可が必要です。設定から許可してください。");}
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
            public void onConfigured(CameraCaptureSession s){if(!active){s.close();return;}session=s;synchronized(lock){ring.clear();firstFrame=0;armed=captured.isEmpty();}configureRequest();}
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
                ui.post(()->{if(!armed || !active)return;preview.setImageBitmap(bitmap);trigger.setEnabled(ok);status.setText(ok?"待機中 · 音がしたら保存":"直前1秒を準備中…");detail.setText(String.format(Locale.JAPAN,"%d × %d · 実測 %.1f fps · %dコマ",width,height,duration>0?(n-1)*1e9/duration:0,n));});
            }
        }catch(Exception e){fail("画像取得エラー: "+e.getMessage());}
    }
    Bitmap bitmap(Frame f){
        int[] pixels=new int[f.y.length];for(int i=0;i<pixels.length;i++){int y=f.y[i]&255;pixels[i]=0xff000000|(y<<16)|(y<<8)|y;}
        Bitmap raw=Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888);
        Matrix m=new Matrix();m.postRotate(rotation);Bitmap result=Bitmap.createBitmap(raw,0,0,width,height,m,true);if(result!=raw)raw.recycle();return result;
    }
    void capture(){
        pressed=SystemClock.elapsedRealtimeNanos();
        synchronized(lock){armed=false;captured=new ArrayList<>();for(Frame f:ring)if(f.time<=pressed&&pressed-f.time<=1_000_000_000L)captured.add(f);ring.clear();}
        trigger.setEnabled(false);rate.setEnabled(false);
        if(captured.isEmpty()){status.setText("保存対象がありません。撮影を再開してください。");resume.setEnabled(true);return;}
        scrub.setMax(captured.size()-1);scrub.setProgress(captured.size()-1);scrub.setEnabled(true);showFrame(captured.size()-1);
        status.setText("保存中…");
        ArrayList<Frame> frames=new ArrayList<>(captured);final long triggerTime=pressed;
        files.execute(()->{
            try{
                File folder=new File(getFilesDir(),"captures");folder.mkdirs();
                File dest=new File(folder,"spark-"+new java.text.SimpleDateFormat("yyyyMMdd-HHmmss-SSS",Locale.US).format(new Date())+".zip");
                try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(dest))){
                    StringBuilder csv=new StringBuilder("file,received_relative_ms,sensor_timestamp_ns\n");
                    for(int i=0;i<frames.size();i++){
                        Frame f=frames.get(i);String name=String.format(Locale.US,"frame-%03d.jpg",i+1);
                        zip.putNextEntry(new ZipEntry(name));Bitmap bm=bitmap(f);bm.compress(Bitmap.CompressFormat.JPEG,85,zip);bm.recycle();zip.closeEntry();
                        csv.append(String.format(Locale.US,"%s,%.3f,%d\n",name,(f.time-triggerTime)/1e6,f.sensor));
                    }
                    zip.putNextEntry(new ZipEntry("timing.csv"));zip.write(csv.toString().getBytes("UTF-8"));zip.closeEntry();
                }
                ui.post(()->{archive=dest;status.setText(frames.size()+"コマを端末内に保存済み");resume.setEnabled(true);export.setEnabled(true);});
            }catch(Exception e){ui.post(()->{status.setText("保存失敗: "+e.getMessage());resume.setEnabled(true);});}
        });
    }
    void showFrame(int i){if(i<0||i>=captured.size())return;Frame f=captured.get(i);preview.setImageBitmap(bitmap(f));detail.setText(String.format(Locale.JAPAN,"%d / %d コマ · ボタンの %.0f ms前",i+1,captured.size(),(pressed-f.time)/1e6));}
    protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==10&&result==RESULT_OK&&data!=null&&archive!=null){File source=archive;Uri uri=data.getData();files.execute(()->{try(InputStream in=new FileInputStream(source);OutputStream out=getContentResolver().openOutputStream(uri)){if(out==null)throw new IOException("保存先を開けません");byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);ui.post(()->status.setText("ZIPを書き出しました"));}catch(Exception e){ui.post(()->status.setText("書き出し失敗: "+e.getMessage()));}});}}
    void fail(String message){synchronized(lock){armed=false;}ui.post(()->{status.setText(message);trigger.setEnabled(false);});}
    protected void onPause(){
        active=false;synchronized(lock){armed=false;ring.clear();}trigger.setEnabled(false);
        if(cameraHandler!=null){HandlerThread old=thread;cameraHandler.post(()->{if(session!=null){session.close();session=null;}if(camera!=null){camera.close();camera=null;}if(reader!=null){reader.close();reader=null;}old.quitSafely();});try{old.join(2000);}catch(InterruptedException e){Thread.currentThread().interrupt();}thread=null;cameraHandler=null;}
        super.onPause();
    }
}
