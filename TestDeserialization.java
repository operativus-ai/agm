import com.fasterxml.jackson.databind.ObjectMapper;
import com.operativus.agentmanager.core.model.definitions.AgentDefinition;

public class TestDeserialization {
    public static void main(String[] args) throws Exception {
        String json = "{\"agentId\":\"test\",\"name\":\"Test\",\"description\":\"desc\",\"instructions\":\"inst\",\"model\":\"mod\",\"preHooks\":[\"ext-123\"]}";
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        AgentDefinition def = mapper.readValue(json, AgentDefinition.class);
        System.out.println("configuration block: " + def.configuration());
    }
}
