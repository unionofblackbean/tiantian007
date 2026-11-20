package dev.superwy;

import dev.superwy.network.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class FrpTunnelTest {
    @TempDir Path dir;
    static class Fake implements FrpTunnel.Child {
        boolean alive=true;final String text;Fake(String text){this.text=text;}
        public InputStream output(){return new ByteArrayInputStream(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        public boolean alive(){return alive;}public void stop(){alive=false;}
    }
    FrpSettings settings(){var s=new FrpSettings();s.authToken="private-test-key";return s;}
    void await(FrpTunnel f,String state)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!f.status().get("state").equals(state)&&System.nanoTime()<end)Thread.sleep(10);assertEquals(state,f.status().get("state"),f.status().toString());}
    @Test void conflictChoosesDifferentPortReadyStateHidesSecretsAndStopKillsOnlyOwnedChild()throws Exception{
        var ports=new CopyOnWriteArrayList<String>();var children=new CopyOnWriteArrayList<Fake>();var s=settings();
        try(var f=new FrpTunnel(s,54321,dir,(exe,cfg)->{
            String text=Files.readString(cfg);ports.add(text.substring(text.indexOf("remotePort = ")));assertTrue(text.contains("localPort = 54321"));
            var p=new Fake(children.isEmpty()?"start error: port already used\n":"start proxy success\n");children.add(p);return p;
        },()->dir.resolve("fake.exe"))){
            f.start();await(f,"ready");assertEquals(2,children.size());assertNotEquals(ports.get(0),ports.get(1));assertFalse(children.getFirst().alive());assertFalse(f.status().toString().contains(s.authToken));assertTrue(f.readyUrl().startsWith("http://frp.example.com:"));
            f.start();assertEquals(2,children.size());f.stop();assertFalse(children.getLast().alive());assertThrows(IllegalArgumentException.class,f::readyUrl);assertEquals("stopped",f.status().get("state"));
        }
    }
    @Test void authenticationAndForbiddenPortsFailWithoutEndlessRetry()throws Exception{
        for(String log:List.of("login to server failed: token in login doesn't match token from configuration\n","start error: port not allowed\n")){
            var launches=new AtomicInteger();try(var f=new FrpTunnel(settings(),12345,dir,(exe,cfg)->{launches.incrementAndGet();return new Fake(log);},()->dir.resolve("fake.exe"))){f.start();await(f,"failed");assertEquals(1,launches.get());assertFalse(f.status().toString().contains("private-test-key"));}
        }
    }
    @Test void startupTimeoutAndExhaustedRangeReleaseChild()throws Exception{
        var s=settings();s.startupSeconds=1;var p=new Fake("");
        try(var f=new FrpTunnel(s,12345,dir,(exe,cfg)->p,()->dir.resolve("fake.exe"))){f.start();await(f,"failed");assertFalse(p.alive());}
        s.minPort=s.maxPort=23456;var launches=new AtomicInteger();
        try(var f=new FrpTunnel(s,12345,dir,(exe,cfg)->{launches.incrementAndGet();return new Fake("start error: port already used\n");},()->dir.resolve("fake.exe"))){f.start();await(f,"failed");assertEquals(1,launches.get());}
    }
    @Test void stopDuringBinaryPreparationCannotLaunchLater()throws Exception{
        var begin=new CountDownLatch(1);var release=new CountDownLatch(1);var launches=new AtomicInteger();
        try(var f=new FrpTunnel(settings(),12345,dir,(exe,cfg)->{launches.incrementAndGet();return new Fake("");},()->{begin.countDown();release.await();return dir.resolve("fake.exe");})){
            f.start();assertTrue(begin.await(2,TimeUnit.SECONDS));f.stop();release.countDown();Thread.sleep(100);assertEquals(0,launches.get());assertEquals("stopped",f.status().get("state"));
        }
    }
    @Test void successfulPortSurvivesReloadAndIsReusedBeforeRandomFallback()throws Exception{
        Path path=dir.resolve("config.json");var c=new dev.superwy.core.Config();c.frp=settings();c.frp.lastRemotePort=23456;c.save(path);
        var ports=new CopyOnWriteArrayList<Integer>();var conflict=new AtomicBoolean(true);
        FrpTunnel.Launcher launch=(exe,cfg)->{
            String text=Files.readString(cfg);int port=Integer.parseInt(text.substring(text.indexOf("remotePort = ")+13).trim());ports.add(port);
            return new Fake(conflict.getAndSet(false)?"start error: port already used\n":"start proxy success\n");
        };
        try(var f=new FrpTunnel(c.frp,54321,dir,launch,()->dir.resolve("fake.exe"),()->c.save(path))){
            f.start();await(f,"ready");assertEquals(23456,ports.getFirst());assertNotEquals(23456,ports.getLast());
            assertEquals(ports.getLast(),dev.superwy.core.Config.load(path).frp.lastRemotePort);
        }
        var reloaded=dev.superwy.core.Config.load(path);ports.clear();
        try(var f=new FrpTunnel(reloaded.frp,54322,dir,launch,()->dir.resolve("fake.exe"),()->reloaded.save(path))){f.start();await(f,"ready");assertEquals(List.of(reloaded.frp.lastRemotePort),ports);}
    }
    @Test void failedRegistrationDoesNotReplaceRememberedPortAndSaveFailureClosesTunnel()throws Exception{
        var s=settings();s.lastRemotePort=23456;
        try(var f=new FrpTunnel(s,12345,dir,(exe,cfg)->new Fake("start error: port not allowed\n"),()->dir.resolve("fake.exe"),()->fail("failed registration must not persist"))){f.start();await(f,"failed");assertEquals(23456,s.lastRemotePort);}
        var child=new Fake("start proxy success\n");s.lastRemotePort=0;
        try(var f=new FrpTunnel(s,12345,dir,(exe,cfg)->child,()->dir.resolve("fake.exe"),()->{throw new IOException("fixture");})){
            f.start();await(f,"failed");assertEquals(0,s.lastRemotePort);assertNull(f.status().get("publicUrl"));assertTrue(f.status().get("errorMessage").toString().contains("保存失败"));
        }assertFalse(child.alive());
    }
}
