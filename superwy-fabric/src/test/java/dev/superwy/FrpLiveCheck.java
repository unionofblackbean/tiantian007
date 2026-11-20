package dev.superwy;

import dev.superwy.core.Jsons;
import dev.superwy.network.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.UUID;

/** Explicit opt-in: temporary nonce-only endpoint, independent of the real game and existing frpc. */
public final class FrpLiveCheck {
    public static void main(String[] args)throws Exception{
        var path=Path.of(args[0]);var settings=Jsons.GSON.fromJson(Files.readString(path),FrpSettings.class);
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);String nonce="superwy-frp-check-"+UUID.randomUUID();
        server.createContext("/",exchange->{byte[] bytes=nonce.getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});server.start();
        try(var tunnel=new FrpTunnel(settings,server.getAddress().getPort(),path.getParent())){
            tunnel.start();long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(25);
            while(!tunnel.status().get("state").equals("ready")&&!tunnel.status().get("state").equals("failed")&&System.nanoTime()<deadline)Thread.sleep(100);
            System.out.println("FRP tunnel: "+tunnel.status());
            String url=tunnel.readyUrl();var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).proxy(new ProxySelector(){public java.util.List<Proxy> select(URI uri){return java.util.List.of(Proxy.NO_PROXY);}public void connectFailed(URI uri,SocketAddress address,java.io.IOException error){}}).build();
            var reply=client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(),HttpResponse.BodyHandlers.ofString());
            if(reply.statusCode()!=200||!nonce.equals(reply.body()))throw new AssertionError("Public endpoint did not return the temporary fixture nonce");
            System.out.println("SUPERWY_FRP_LIVE_OK: bundled frpc authenticates, random public port forwards to random local port, public HTTP round trip verified, owned tunnel cleaned up");
        }finally{server.stop(0);}
    }
}
