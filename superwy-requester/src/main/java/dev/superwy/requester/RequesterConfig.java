package dev.superwy.requester;

import com.google.gson.*;
import java.nio.file.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import dev.superwy.protocol.WhisperProtocol;

public final class RequesterConfig {
    public String baseUrl="http://127.0.0.1:8765",token="",fakePlayerName="sf",warehouseId="main",stationId="main";
    // Orders go over HTTP only; botName and shortKey are kept so older config files still load.
    public String transport="http",botName="",shortKey="",defaultItem="minecraft:stone";
    /** Super-dispatch: required by robots running in mesh mode; orders are routed to the dispatcher robot. */
    public boolean mesh=false;
    public int defaultCount=64;
    public int pickupKey=-3,confirmKey=257,queueKey=92;
    public boolean nativeHotkeysInitialized;
    public double pickRange=8;
    public static final Gson GSON=new GsonBuilder().setPrettyPrinting().create();
    public static RequesterConfig load(Path path)throws Exception{
        Files.createDirectories(path.getParent());
        if(!Files.exists(path)){var c=new RequesterConfig();c.save(path);return c;}
        var config=GSON.fromJson(Files.readString(path,StandardCharsets.UTF_8),RequesterConfig.class);
        if(config==null)throw new IllegalArgumentException("配置为空");
        config.transport="http";
        if(!Double.isFinite(config.pickRange)||config.pickRange<1||config.pickRange>64)throw new IllegalArgumentException("pickRange 必须在 1–64 之间");
        config.validateSettings();return config;
    }
    public void save(Path path)throws Exception{
        validateSettings();Files.createDirectories(path.getParent());Path temp=path.resolveSibling("config.json.tmp");
        try(var out=java.nio.channels.FileChannel.open(temp,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)){
            var buffer=java.nio.ByteBuffer.wrap((GSON.toJson(this)+"\n").getBytes(StandardCharsets.UTF_8));while(buffer.hasRemaining())out.write(buffer);out.force(true);
        }
        try{Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING);}
    }
    public RequesterConfig copy(){return GSON.fromJson(GSON.toJson(this),RequesterConfig.class);}
    public RequesterConfig withConnection(String code){
        var settings=dev.superwy.protocol.ConnectionCode.decode(code);var draft=copy();
        draft.baseUrl=settings.url();draft.token=settings.token();draft.fakePlayerName=settings.recipient().isEmpty()?"sf":settings.recipient();draft.botName=settings.bot();draft.warehouseId=settings.warehouse();draft.transport="http";draft.validateOrder();return draft;
    }
    public void validateSettings(){
        if(!RequestHotkey.valid(pickupKey)||!RequestHotkey.valid(confirmKey)||!RequestHotkey.valid(queueKey))throw new IllegalArgumentException("快捷键无效");
        if(!nativeHotkeysInitialized)validateDistinctHotkeys();
        if(!"chat".equals(transport)&&!"http".equals(transport))throw new IllegalArgumentException("发送方式只能为 chat 或 http");
        if(botName==null||!botName.isEmpty()&&!WhisperProtocol.name(botName)||fakePlayerName==null||!fakePlayerName.isEmpty()&&!WhisperProtocol.name(fakePlayerName))throw new IllegalArgumentException("玩家名只能用 1–16 位字母数字下划线");
        if(!WhisperProtocol.key(shortKey))throw new IllegalArgumentException("短 key 留空或填写 5 位字母数字");
        if(defaultItem==null||!defaultItem.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")||defaultCount<1||defaultCount>64)throw new IllegalArgumentException("默认物品需为物品 ID，数量为 1–64");
        if(!Double.isFinite(pickRange)||pickRange<1||pickRange>64)throw new IllegalArgumentException("pickRange 必须在 1–64 之间");
        endpoint();
    }
    public void validateDistinctHotkeys(){
        if(pickupKey!=RequestHotkey.UNBOUND&&(pickupKey==confirmKey||pickupKey==queueKey)||confirmKey!=RequestHotkey.UNBOUND&&confirmKey==queueKey)throw new IllegalArgumentException("三个快捷键不能重复，请在原版按键设置中检查冲突");
    }
    public URI endpoint(){
        URI u=URI.create(baseUrl);
        if(!("http".equals(u.getScheme())||"https".equals(u.getScheme()))||u.getHost()==null||u.getRawUserInfo()!=null||u.getRawQuery()!=null||u.getRawFragment()!=null||!(u.getPath().isEmpty()||u.getPath().equals("/")))throw new IllegalArgumentException("baseUrl 应为 http(s)://主机:端口");
        return u.resolve("/api/v1/orders");
    }
    public void validateOrder(){
        endpoint();
        if(token==null||!token.matches("[A-Za-z0-9._~+/=-]{32,256}"))throw new IllegalArgumentException("请在配置填写玩家 API token（至少 32 位）");
        if(fakePlayerName==null||!fakePlayerName.matches("[A-Za-z0-9_]{1,16}"))throw new IllegalArgumentException("请配置 fakePlayerName，须在机器人允许名单中");
        if(warehouseId==null||stationId==null)throw new IllegalArgumentException("请配置仓库和交付站");
    }
}
