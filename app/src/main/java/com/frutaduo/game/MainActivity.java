package com.frutaduo.game;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.*;
import android.view.WindowManager;
import android.net.wifi.WifiManager;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class MainActivity extends Activity {
    private WebView webView;
    private volatile String roomCode, roomOffer;
    private volatile ServerSocket server;
    private volatile Socket guestSocket;
    private volatile boolean running;
    private WifiManager.MulticastLock multicastLock;
    private static final int DISCOVERY_PORT=39173;

    private class LanBridge {
        @JavascriptInterface public void host(String code,String offer) {
            roomCode=code; roomOffer=offer; running=true;
            new Thread(() -> hostLoop(), "fruit-host").start();
            new Thread(() -> discoveryLoop(), "fruit-discovery").start();
        }
        @JavascriptInterface public void join(String code) {
            new Thread(() -> joinRoom(code), "fruit-join").start();
        }
        @JavascriptInterface public void stop() { running=false; try{if(server!=null)server.close();}catch(Exception ignored){} try{if(guestSocket!=null)guestSocket.close();}catch(Exception ignored){} }
        @JavascriptInterface public void answer(String sdp) {
            Socket s=guestSocket;
            if(s==null)return;
            new Thread(() -> {try{
                PrintWriter out=new PrintWriter(new OutputStreamWriter(s.getOutputStream(),StandardCharsets.UTF_8),true);
                out.println("ANSWER|"+encode(sdp));
            }catch(Exception e){callback("onLanError",e.getMessage());}}, "fruit-answer").start();
        }
    }

    private void hostLoop(){
        try(ServerSocket ss=new ServerSocket(0)){
            server=ss; ss.setSoTimeout(1000);
            while(running){
                try(Socket s=ss.accept()){
                    s.setSoTimeout(30000);
                    BufferedReader in=new BufferedReader(new InputStreamReader(s.getInputStream(),StandardCharsets.UTF_8));
                    PrintWriter out=new PrintWriter(new OutputStreamWriter(s.getOutputStream(),StandardCharsets.UTF_8),true);
                    String first=in.readLine();
                    if(first==null||!first.equals("JOIN|"+roomCode))continue;
                    out.println("OFFER|"+encode(roomOffer));
                    String line=in.readLine();
                    if(line!=null&&line.startsWith("ANSWER|"))callback("onLanAnswer",decode(line.substring(7)));
                }catch(SocketTimeoutException ignored){}
            }
        }catch(Exception e){if(running)callback("onLanError",e.getMessage());}
    }
    private void discoveryLoop(){
        try(DatagramSocket ds=new DatagramSocket(DISCOVERY_PORT)){
            ds.setBroadcast(true); ds.setSoTimeout(1000);
            byte[] buf=new byte[128];
            while(running){
                try{
                    DatagramPacket p=new DatagramPacket(buf,buf.length);ds.receive(p);
                    String msg=new String(p.getData(),p.getOffset(),p.getLength(),StandardCharsets.UTF_8);
                    ServerSocket ss=server;
                    if(msg.equals("FIND|"+roomCode)&&ss!=null){
                        byte[] reply=("HERE|"+roomCode+"|"+ss.getLocalPort()).getBytes(StandardCharsets.UTF_8);
                        ds.send(new DatagramPacket(reply,reply.length,p.getAddress(),p.getPort()));
                    }
                }catch(SocketTimeoutException ignored){}
            }
        }catch(Exception e){if(running)callback("onLanError",e.getMessage());}
    }
    private void joinRoom(String code){
        try(DatagramSocket ds=new DatagramSocket()){
            ds.setBroadcast(true);ds.setSoTimeout(7000);
            byte[] query=("FIND|"+code).getBytes(StandardCharsets.UTF_8);
            DatagramPacket q=new DatagramPacket(query,query.length,InetAddress.getByName("255.255.255.255"),DISCOVERY_PORT);
            String ip=null;int port=-1;long until=System.currentTimeMillis()+6500;
            while(System.currentTimeMillis()<until&&ip==null){
                ds.send(q);
                try{
                    byte[] buf=new byte[128];DatagramPacket p=new DatagramPacket(buf,buf.length);ds.receive(p);
                    String msg=new String(p.getData(),p.getOffset(),p.getLength(),StandardCharsets.UTF_8);
                    String[] parts=msg.split("\\|");
                    if(parts.length==3&&parts[0].equals("HERE")&&parts[1].equals(code)){ip=p.getAddress().getHostAddress();port=Integer.parseInt(parts[2]);}
                }catch(SocketTimeoutException ignored){}
            }
            if(ip==null)throw new IOException("Sala não encontrada. Confira o código e se os dois estão no mesmo Wi-Fi.");
            Socket s=new Socket();s.connect(new InetSocketAddress(ip,port),5000);s.setSoTimeout(30000);guestSocket=s;
            BufferedReader in=new BufferedReader(new InputStreamReader(s.getInputStream(),StandardCharsets.UTF_8));
            PrintWriter out=new PrintWriter(new OutputStreamWriter(s.getOutputStream(),StandardCharsets.UTF_8),true);
            out.println("JOIN|"+code);
            String line=in.readLine();
            if(line==null||!line.startsWith("OFFER|"))throw new IOException("Não foi possível receber a oferta da sala.");
            callback("onLanOffer",decode(line.substring(6)));
            String answer=in.readLine();
            if(answer==null||!answer.startsWith("ANSWER|"))throw new IOException("A resposta da conexão não chegou.");
        }catch(Exception e){callback("onLanError",e.getMessage());}
    }
    private static String encode(String s){return android.util.Base64.encodeToString(s.getBytes(StandardCharsets.UTF_8),android.util.Base64.NO_WRAP);}
    private static String decode(String s){return new String(android.util.Base64.decode(s,android.util.Base64.DEFAULT),StandardCharsets.UTF_8);}
    private void callback(String fn,String value){
        runOnUiThread(() -> {if(webView!=null)webView.evaluateJavascript("window."+fn+"("+JSONObject.quote(value==null?"":value)+")",null);});
    }

    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WifiManager wm=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
        if(wm!=null){multicastLock=wm.createMulticastLock("fruit-duo-lan");multicastLock.setReferenceCounted(false);multicastLock.acquire();}
        webView=new WebView(this);WebSettings settings=webView.getSettings();
        settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);settings.setMediaPlaybackRequiresUserGesture(false);
        webView.addJavascriptInterface(new LanBridge(),"AndroidLan");
        webView.setWebViewClient(new WebViewClient());webView.setWebChromeClient(new WebChromeClient());
        setContentView(webView);webView.loadUrl("file:///android_asset/index.html");
    }
    @Override public void onBackPressed(){if(webView!=null&&webView.canGoBack())webView.goBack();else super.onBackPressed();}
    @Override protected void onDestroy(){
        running=false;try{if(server!=null)server.close();}catch(Exception ignored){}
        try{if(guestSocket!=null)guestSocket.close();}catch(Exception ignored){}
        if(multicastLock!=null&&multicastLock.isHeld())multicastLock.release();
        if(webView!=null){webView.loadUrl("about:blank");webView.destroy();webView=null;}super.onDestroy();
    }
}