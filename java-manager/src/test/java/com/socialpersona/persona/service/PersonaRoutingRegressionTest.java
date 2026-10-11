package com.socialpersona.persona.service;

import com.socialpersona.event.scheduler.EventTriggerScheduler;
import com.socialpersona.gateway.PythonClient;
import com.socialpersona.message.scheduler.MessageScanScheduler;
import com.socialpersona.persona.dto.PersonaConfigDTO;
import com.socialpersona.persona.entity.Persona;
import com.socialpersona.persona.repository.CharacterLifeArchiveMapper;
import com.socialpersona.persona.repository.PersonaMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PersonaRoutingRegressionTest {
    private static final Path testDir = temporaryDirectory();

    private static Path temporaryDirectory() {
        try { return Files.createTempDirectory("persona-routing-"); }
        catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }

    @DynamicPropertySource
    static void isolatedStorage(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> "jdbc:sqlite:" + testDir.resolve("test.db"));
        properties.add("app.config-file", () -> testDir.resolve("config.json").toString());
        properties.add("app.image-dir", () -> testDir.resolve("images").toString());
        properties.add("app.automation.enabled", () -> "false");
        properties.add("spring.cache.type", () -> "simple");
        properties.add("spring.data.redis.enabled", () -> "false");
    }

    @MockBean private PythonClient pythonClient;
    @MockBean private EventTriggerScheduler eventScheduler;
    @MockBean private MessageScanScheduler messageScheduler;
    @Autowired private PersonaService service;
    @Autowired private PersonaMapper personas;
    @Autowired private CharacterLifeArchiveMapper archives;

    @BeforeEach
    void resetFixtures() throws Exception {
        archives.delete(null);
        personas.delete(null);
        service.setAllOwnerQq("unused"); // Clear persona caches between independent fixtures.
        Files.writeString(testDir.resolve("config.json"), "{\"qq\":\" 888888 \"}");
    }

    @Test
    void firstManualPersonaInheritsGlobalOwner() {
        String id = service.createFromJson("{\"name\":\"manual\",\"bigFiveJson\":\"{}\",\"ownerQq\":\"\"}");
        assertEquals("888888", personas.selectById(id).getOwnerQq());
    }

    @Test
    void firstInterviewPersonaInheritsGlobalOwner() {
        PersonaConfigDTO config = new PersonaConfigDTO();
        config.setName("interview");
        Persona created = service.createPersona(config, "", "local", null);
        assertEquals("888888", personas.selectById(created.getId()).getOwnerQq());
    }

    @Test
    void importedPersonaInheritsGlobalOwner() {
        String id = service.importFromJson("{\"name\":\"imported\",\"bigFiveJson\":\"{}\"}");
        assertEquals("888888", personas.selectById(id).getOwnerQq());
    }

    @Test
    void bindingFirstExistingPersonaUsesGlobalOwner() {
        Persona persona = fixture("first", "10001", null);
        personas.insert(persona);

        service.bindChannel("first", "qq", "10002");

        assertEquals("888888", personas.selectById("first").getOwnerQq());
    }

    @Test
    void reassignmentPersistsNullOnPreviousOwner() {
        personas.insert(fixture("first", "10001", "888888"));
        personas.insert(fixture("second", "10002", "888888"));

        service.bindChannel("second", "qq", "10001");

        assertNull(personas.selectById("first").getAiQq());
        assertEquals("second", service.findByAiQQ("10001").getId());
    }

    @Test
    void pausingInvalidatesCachedQqRoute() {
        personas.insert(fixture("first", "10001", "888888"));
        assertEquals("active", service.findByAiQQ("10001").getStatus());

        service.toggle("first");

        assertEquals("paused", service.findByAiQQ("10001").getStatus());
    }

    @Test
    void explicitOwnerIsPreserved() {
        String id = service.createFromJson("{\"name\":\"manual\",\"bigFiveJson\":\"{}\",\"ownerQq\":\"777777\"}");
        assertEquals("777777", personas.selectById(id).getOwnerQq());
    }

    @Test
    void eventCredentialsFollowCurrentGlobalSettings() throws Exception {
        PersonaConfigDTO config = new PersonaConfigDTO();
        config.setName("credentials");
        Persona created = service.createPersona(config, "old-test-key", "old-master", null);
        String currentKey = Base64.getEncoder().encodeToString("new-test-key".getBytes(StandardCharsets.UTF_8));
        Files.writeString(testDir.resolve("config.json"), "{\"provider\":\"custom\",\"baseUrl\":\"http://127.0.0.1:9000/v1\","
                + "\"model\":\"new-model\",\"apiKeyEncrypted\":\"" + currentKey + "\",\"masterKeyHash\":\"changed\"}");

        var current = service.decryptApiConfig(created.getId());

        assertNotNull(current);
        assertEquals("new-test-key", current.getApiKey());
        assertEquals("custom", current.getProvider());
        assertEquals("new-model", current.getModel());
    }

    private Persona fixture(String id, String aiQq, String ownerQq) {
        Persona persona = new Persona();
        persona.setId(id);
        persona.setName(id);
        persona.setStatus("active");
        persona.setBigFiveJson("{}");
        persona.setAiQq(aiQq);
        persona.setOwnerQq(ownerQq);
        return persona;
    }
}
