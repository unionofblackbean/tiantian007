package dev.superwy.network;

import dev.superwy.core.Jsons;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

/** Owns one child process. No shell commands and no credential-bearing output is logged. */
public final class FrpTunnel implements AutoCloseable {
    public interface Child {InputStream output();boolean alive();void stop();}
    @FunctionalInterface public interface Launcher {Child launch(Path executable,Path config)throws IOException;}
    @FunctionalInterface public interface Binary {Path prepare()throws Exception;}
    @FunctionalInterface public interface Persistence {void save()throws Exception;}
    private final Persistence persistence;
    private final FrpSettings settings;private final int localPort;private final Path directory;private final Launcher launcher;private final Binary binary;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"superwy-frp");t.setDaemon(true);return t;});
    private volatile Child child;private volatile long generation;private volatile String state="stopped",error,publicUrl;
    private volatile int remotePort,attempts;private boolean closed;
    public FrpTunnel(FrpSettings settings,int localPort,Path directory){this(settings,localPort,directory,()->{});}
    public FrpTunnel(FrpSettings settings,int localPort,Path directory,Persistence persistence){this(settings,localPort,directory,FrpTunnel::launch,()->extract(directory),persistence);}
    public FrpTunnel(FrpSettings settings,int localPort,Path directory,Launcher launcher,Binary binary){this(settings,localPort,directory,launcher,binary,()->{});}
    public FrpTunnel(FrpSettings settings,int localPort,Path directory,Launcher launcher,Binary binary,Persistence persistence){this.settings=settings;this.localPort=localPort;this.directory=directory;this.launcher=launcher;this.binary=binary;this.persistence=persistence;}
    public synchronized void start(){
        if(closed)throw new IllegalStateException("FRP 已关闭");
        settings.validate();if(settings.authToken.isEmpty())throw new IllegalArgumentException("FRP 尚未配置认证凭证");
        if(Set.of("starting","ready","reconnecting").contains(state))return;
        long run=++generation;state="starting";error=null;publicUrl=null;attempts=0;worker.submit(()->run(run));
    }
    public synchronized void stop(){generation++;publicUrl=null;state="stopped";error=null;var current=child;child=null;if(current!=null)current.stop();}
    public Map<String,Object> status(){
        var view=new LinkedHashMap<String,Object>();view.put("state",state);view.put("errorMessage",error);view.put("localPort",localPort);view.put("remotePort",remotePort);view.put("publicUrl",publicUrl);view.put("attempts",attempts);view.put("serverAddr",settings.serverAddr);view.put("serverPort",settings.serverPort);return view;
    }
    public String readyUrl(){if(!"ready".equals(state)||publicUrl==null)throw new IllegalArgumentException("FRP_NOT_READY");return publicUrl;}
    private synchronized boolean current(long run){return run==generation&&!closed;}
    private synchronized boolean attach(long run,Child next){if(!current(run)){next.stop();return false;}child=next;return true;}
    private synchronized void update(long run,String next,String message,String url){if(current(run)){error=message;publicUrl=url;state=next;}}
    private synchronized boolean registered(long run,int port){
        if(!current(run))return false;
        int previous=settings.lastRemotePort;settings.lastRemotePort=port;
        try{persistence.save();}
        catch(Exception failure){settings.lastRemotePort=previous;update(run,"failed","FRP 公网端口保存失败，请检查配置文件写入权限后重试",null);return false;}
        update(run,"ready",null,"http://"+settings.serverAddr+":"+port);return true;
    }
    private void run(long run){
        try{
            Path executable=binary.prepare();Files.createDirectories(directory);
            var used=new HashSet<Integer>();var random=new SecureRandom();
            for(int attempt=1;attempt<=settings.maxAttempts&&current(run);attempt++){
                int port;
                if(attempt==1&&settings.lastRemotePort>=settings.minPort&&settings.lastRemotePort<=settings.maxPort)port=settings.lastRemotePort;
                else do{port=settings.minPort+random.nextInt(settings.maxPort-settings.minPort+1);}while(used.contains(port)&&used.size()<settings.maxPort-settings.minPort+1);
                if(!used.add(port)){update(run,"failed","FRP 可用端口范围已尝试完毕",null);return;}
                remotePort=port;attempts=attempt;
                Path file=directory.resolve("frp-managed.toml");Files.writeString(file,toml(settings,localPort,port,"superwy-"+UUID.randomUUID()),StandardCharsets.UTF_8);
                var running=launcher.launch(executable,file);if(!attach(run,running))return;
                var lines=new LinkedBlockingQueue<String>(128);
                Thread.ofVirtual().name("superwy-frp-output").start(()->{
                    try(var reader=new BufferedReader(new InputStreamReader(running.output(),StandardCharsets.UTF_8))){String line;while((line=reader.readLine())!=null)lines.offer(line);}
                    catch(IOException ignored){}
                });
                boolean ready=false,retry=false;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(settings.startupSeconds);
                try{
                    while(current(run)){
                        String line=lines.poll(100,TimeUnit.MILLISECONDS);
                        if(line!=null){String text=line.toLowerCase(Locale.ROOT);
                            if(text.contains("start proxy success")){if(!registered(run,port))return;ready=true;}
                            else if(text.contains("start error")&&(text.contains("port already")||text.contains("port is already")||text.contains("address already in use"))){retry=true;break;}
                            else if(text.contains("token")&&(text.contains("not match")||text.contains("doesn't match")||text.contains("invalid")||text.contains("incorrect"))){update(run,"failed","FRP 认证失败，请检查服务端凭证",null);return;}
                            else if(text.contains("start error")){update(run,"failed","FRP 服务端拒绝隧道，请检查允许的端口范围和服务端配置",null);return;}
                            else if(text.contains("login to server failed")||text.contains("connect to server error")||text.contains("connection closed")){ready=false;update(run,"reconnecting","FRP 连接中断，正在等待重新连接",null);deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(settings.startupSeconds);}
                        }
                        if(!running.alive()&&lines.isEmpty()){update(run,"failed","FRP 进程已退出，请检查地址、网络及认证配置",null);return;}
                        if(!ready&&System.nanoTime()>deadline){update(run,"failed","FRP 连接超时，请检查服务端和网络",null);return;}
                    }
                }finally{running.stop();synchronized(this){if(child==running)child=null;}}
                if(!current(run))return;
                if(!retry)return;
                update(run,"starting","公网端口冲突，正在随机选择新端口",null);
            }
            update(run,"failed","随机公网端口多次冲突，请重试或调整端口范围",null);
        }catch(InterruptedException error){Thread.currentThread().interrupt();}
        catch(Exception error){update(run,"failed",error instanceof UnsupportedOperationException?"内置 FRP 仅支持 Windows x64，请使用匹配平台的 frpc":"FRP 启动失败，请检查本地文件权限和运行环境",null);}
    }
    public static String toml(FrpSettings settings,int local,int remote,String name){
        return "serverAddr = "+quote(settings.serverAddr)+"\nserverPort = "+settings.serverPort+"\nloginFailExit = true\nauth.token = "+quote(settings.authToken)+"\nlog.to = \"console\"\nlog.level = \"info\"\n[[proxies]]\nname = "+quote(name)+"\ntype = \"tcp\"\nlocalIP = \"127.0.0.1\"\nlocalPort = "+local+"\nremotePort = "+remote+"\n";
    }
    private static String quote(String value){return Jsons.GSON.toJson(value).replace("\\u003d","=").replace("\\u0027","'").replace("\\u003c","<").replace("\\u003e",">").replace("\\u0026","&");}
    private static Child launch(Path executable,Path config)throws IOException{
        var process=new ProcessBuilder(executable.toAbsolutePath().toString(),"-c",config.toAbsolutePath().toString()).redirectErrorStream(true).start();
        return new Child(){public InputStream output(){return process.getInputStream();}public boolean alive(){return process.isAlive();}public void stop(){process.destroy();if(process.isAlive())process.destroyForcibly();}};
    }
    private static Path extract(Path directory)throws Exception{
        if(!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows")||!Set.of("amd64","x86_64").contains(System.getProperty("os.arch")))throw new UnsupportedOperationException();
        byte[] bytes;String expected;
        try(var input=FrpTunnel.class.getResourceAsStream("/frp/frpc-windows-amd64.exe");var sum=FrpTunnel.class.getResourceAsStream("/frp/frpc.sha256")){
            if(input==null||sum==null)throw new IOException("Bundled frpc unavailable");bytes=input.readAllBytes();expected=new String(sum.readAllBytes(),StandardCharsets.UTF_8).trim();
        }
        if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(expected))throw new IOException("Bundled frpc checksum mismatch");
        Path path=directory.resolve("runtime/frpc-0.71.1.exe");Files.createDirectories(path.getParent());
        if(!Files.exists(path)||!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))).equals(expected)){
            Path tmp=path.resolveSibling("frpc.tmp");Files.write(tmp,bytes);Files.move(tmp,path,StandardCopyOption.REPLACE_EXISTING);
        }return path;
    }
    public synchronized void close(){stop();closed=true;worker.shutdownNow();}
}
