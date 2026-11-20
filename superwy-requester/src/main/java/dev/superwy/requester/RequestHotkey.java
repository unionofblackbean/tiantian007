package dev.superwy.requester;

/** Negative codes encode mouse buttons; nonnegative codes are GLFW keyboard keys. */
public final class RequestHotkey {
    public static final int UNBOUND=Integer.MIN_VALUE;
    private RequestHotkey(){}
    public static int mouse(int button){return -button-1;}
    public static int scanCode(int code){return -1000-code;}
    public static boolean isScanCode(int code){return code<=-1000&&code>=-66535;}
    public static boolean valid(int code){return code==UNBOUND||isScanCode(code)||code>=-8&&code<=348&&code!=0;}
    public static String label(int code){
        if(code==UNBOUND)return "未绑定";
        if(isScanCode(code))return "扫描码 "+(-code-1000);
        if(code<0)return code==-3?"鼠标中键":"鼠标 "+(-code);
        return switch(code){case 257->"Enter";case 335->"小键盘 Enter";case 92->"\\ |";case 32->"空格";default->{String name=org.lwjgl.glfw.GLFW.glfwGetKeyName(code,0);yield name==null?"按键 "+code:name.toUpperCase(java.util.Locale.ROOT);}};
    }
}
