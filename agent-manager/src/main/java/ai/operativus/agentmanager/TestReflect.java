package ai.operativus.agentmanager;
public class TestReflect {
    public static void main(String[] args) {
        java.lang.reflect.Constructor[] ctors = org.springframework.ai.chat.client.ChatClientResponse.class.getDeclaredConstructors();
        for(java.lang.reflect.Constructor c: ctors) {
            System.out.println(c);
        }
    }
}
