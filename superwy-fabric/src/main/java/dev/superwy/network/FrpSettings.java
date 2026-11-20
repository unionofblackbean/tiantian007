package dev.superwy.network;

public final class FrpSettings {
    public boolean enabled=false;
    public String serverAddr="frp.example.com",authToken="";
    public int serverPort=17000,minPort=20000,maxPort=59999,maxAttempts=12,startupSeconds=15;
    public int lastRemotePort=0;
    public void validate(){
        if(serverAddr==null||!serverAddr.matches("[A-Za-z0-9.-]{1,253}")||serverPort<1||serverPort>65535||authToken==null||authToken.length()>4096||minPort<1024||maxPort>65535||minPort>maxPort||maxAttempts<1||maxAttempts>100||startupSeconds<1||startupSeconds>60)throw new IllegalArgumentException("FRP 地址、端口范围或重试设置无效");
        if(enabled&&authToken.isEmpty())throw new IllegalArgumentException("FRP 尚未配置认证凭证");
        if(lastRemotePort!=0&&(lastRemotePort<1024||lastRemotePort>65535))throw new IllegalArgumentException("FRP 已保存的公网端口无效");
    }
}
