package dev.superwy.core;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

public final class Config {
    public String bindAddress="127.0.0.1",worldKey="default";
    public int port=8765;
    public boolean autoPort=true;
    public dev.superwy.network.FrpSettings frp=new dev.superwy.network.FrpSettings();
    public dev.superwy.mesh.MeshSettings mesh=new dev.superwy.mesh.MeshSettings();
    public boolean privatePresetApplied=false;
    public List<String> presetWorlds=new ArrayList<>();
    public String adminToken=UUID.randomUUID()+"-"+UUID.randomUUID();
    public List<String> allowedHosts=new ArrayList<>();
    public boolean automationEnabled=false;
    public boolean manualControl=false;
    public boolean scanEnabled=true;
    public String scanProtocol="f3";
    public int scanPolicyVersion=0;
    public boolean whisperEnabled=true;
    public String whisperKey="";
    public List<Integer> protectedSlots=new ArrayList<>(List.of(0,1,2,3,4,5,6,7,8));
    public int sourceWorkSlot=35,outputBoxSlot=34;
    public Model.Pos returnEntry=new Model.Pos(80,50,-23),boxSupply=new Model.Pos(129,53,-21);
    public int cleanupPolicyVersion=0;
    public boolean cleanupMainInventory=true;
    public int packingRoundSlots=25,maxCarriedParcels=25,packingTimeoutSeconds=86400;
    public List<Credential> credentials=new ArrayList<>();
    public List<Model.Station> stations=new ArrayList<>();
    public static final class Credential {
        public String principalId,token;
        public List<String> fakePlayerNames=new ArrayList<>();
        public boolean recipientSelectable=false;
        public List<String> scopes=new ArrayList<>(List.of("inventory:read","orders:read","orders:write"));
    }
    public record Principal(String id,boolean admin,List<String> names,List<String> scopes,boolean recipientSelectable) {
        public Principal(String id,boolean admin,List<String> names,List<String> scopes){this(id,admin,names,scopes,false);}
        public boolean permits(String name) {return admin||recipientSelectable&&dev.superwy.protocol.WhisperProtocol.name(name)||names.stream().anyMatch(n->n.equalsIgnoreCase(name));}
        public boolean scope(String scope) {return admin||scopes.contains(scope);}
    }
    public static Config load(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        Config c=Files.exists(path)?Jsons.GSON.fromJson(Files.readString(path),Config.class):new Config();
        if(c==null||c.port!=0&&(c.port<1024||c.port>65535)||c.adminToken==null||c.adminToken.length()<32) throw new IllegalArgumentException("Invalid superwy config");
        if(c.frp==null||c.presetWorlds==null)throw new IllegalArgumentException("连接配置不完整");
        if(!c.privatePresetApplied){
            try(var input=Config.class.getResourceAsStream("/superwy-private.json")){
                if(input!=null){c.frp=Jsons.GSON.fromJson(new java.io.InputStreamReader(input,StandardCharsets.UTF_8),dev.superwy.network.FrpSettings.class);c.privatePresetApplied=true;}
            }
        }
        c.frp.validate();
        if(c.mesh==null)c.mesh=new dev.superwy.mesh.MeshSettings();
        c.mesh.validate();
        // Migrate the former drop entrance to the requested storage chest once.
        if(c.cleanupPolicyVersion<1){c.returnEntry=new Model.Pos(80,50,-23);c.cleanupPolicyVersion=1;}
        if(c.returnEntry==null||c.boxSupply==null||c.packingRoundSlots<1||c.packingRoundSlots>25||c.maxCarriedParcels<1||c.maxCarriedParcels>25||c.packingTimeoutSeconds<500||c.packingTimeoutSeconds>86400)throw new IllegalArgumentException("打包参数无效：每轮和货盒上限为 1–25");
        if(!"f3".equals(c.scanProtocol)&&!"servux".equals(c.scanProtocol))throw new IllegalArgumentException("scanProtocol 必须是 f3 或 servux");
        if(!dev.superwy.protocol.WhisperProtocol.key(c.whisperKey))throw new IllegalArgumentException("whisperKey 为空或 5 位字母数字");
        if(c.credentials==null||c.allowedHosts==null||c.stations==null||c.protectedSlots==null||c.protectedSlots.stream().anyMatch(i->i==null||i<0||i>35))throw new IllegalArgumentException("Invalid config collections or protected slots");
        if(c.frp.enabled&&!c.allowedHosts.contains(c.frp.serverAddr))c.allowedHosts.add(c.frp.serverAddr);
        Set<String> principals=new HashSet<>(),tokens=new HashSet<>();tokens.add(c.adminToken);
        for(Credential credential:c.credentials){
            if(credential==null||credential.principalId==null||!credential.principalId.matches("[A-Za-z0-9_.-]{1,64}")||!principals.add(credential.principalId)||credential.token==null||credential.token.length()<32||!tokens.add(credential.token)||credential.fakePlayerNames==null||credential.scopes==null)throw new IllegalArgumentException("Credentials require unique principal IDs and distinct random tokens of at least 32 characters");
            if(credential.fakePlayerNames.stream().anyMatch(n->n==null||!n.matches("[A-Za-z0-9_]{1,16}"))||credential.scopes.stream().anyMatch(s->!Set.of("inventory:read","orders:read","orders:write").contains(s)))throw new IllegalArgumentException("Invalid credential name or scope");
        }
        // Legacy fixed work-slot fields are retained for config compatibility; allocation is dynamic.
        long cargo=java.util.stream.IntStream.range(9,36).filter(i->!c.protectedSlots.contains(i)).count();
        if(cargo<1)throw new IllegalArgumentException("至少保留一个非保护背包槽供取货使用");
        c.save(path);return c;
    }
    public synchronized void save(Path path) throws Exception {
        Path tmp=path.resolveSibling(path.getFileName()+".tmp");
        byte[] data=Jsons.GSON.toJson(this).getBytes(StandardCharsets.UTF_8);
        try(var channel=java.nio.channels.FileChannel.open(tmp,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)){
            var buffer=java.nio.ByteBuffer.wrap(data);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
        }
        try{Files.move(tmp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        catch(AtomicMoveNotSupportedException e){Files.move(tmp,path,StandardCopyOption.REPLACE_EXISTING);}
    }
    public synchronized Principal authenticate(String token) {
        if(token==null)return null;
        if(equal(token,adminToken))return new Principal("admin",true,List.of(),List.of());
        for(Credential c:credentials)if(equal(token,c.token))return new Principal(c.principalId,false,List.copyOf(c.fakePlayerNames),List.copyOf(c.scopes),c.recipientSelectable);
        return null;
    }
    public synchronized Credential playerCredential(String name){
        if(!dev.superwy.protocol.WhisperProtocol.name(name))throw new IllegalArgumentException("INVALID_RECIPIENT_NAME");
        String id="share_"+name.toLowerCase(Locale.ROOT);
        for(var existing:credentials)if(id.equals(existing.principalId)&&existing.fakePlayerNames.equals(List.of(name))&&existing.scopes.equals(List.of("inventory:read","orders:read","orders:write")))return existing;
        credentials.removeIf(c->id.equals(c.principalId));
        var credential=new Credential();credential.principalId=id;byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);credential.token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);credential.fakePlayerNames.add(name);credentials.add(credential);return credential;
    }
    public synchronized Credential connectionCredential(){
        var credential=new Credential();credential.principalId="link_"+UUID.randomUUID();credential.recipientSelectable=true;
        byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);credential.token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);credentials.add(credential);return credential;
    }
    private static boolean equal(String a,String b) {return b!=null&&MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));}
}
