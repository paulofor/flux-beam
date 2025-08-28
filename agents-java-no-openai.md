# FluxBeam — `agents.md` (Java 21 • Spring Boot • Maven • sem OpenAI)

> Documento de orientação para construir o **FluxBeam** em **Java 21 + Spring Boot 3** com **Maven**, focado **exclusivamente** na integração com a **WhatsApp Business Platform – Cloud API** (sem dependências de OpenAI neste momento). P0: hello‑world da Cloud API. P1: mensagens interativas e templates. P2: estrutura de engine plugável.

---

## 0) TL;DR
- **Stack**: Java 21 • Spring Boot 3 (Web) • `RestClient`/`WebClient` para HTTP • Logback.
- **Meta P0**: `/webhook` (GET valida / POST recebe) + verificação **X‑Hub‑Signature‑256** + **eco/saudação** via `/PHONE_NUMBER_ID/messages`.
- **Meta P1**: enviar **listas/botões** (interactive messages) e **template** (fora da janela 24h).
- **Meta P2**: **engine** com SPI de plugins (agenda, suporte etc.), helpers para Flows/templating, idempotência e filas.

---

## 1) Estrutura do repositório
```
fluxbeam/
├─ pom.xml
├─ src/
│  ├─ main/java/com/fluxbeam/app/
│  │  ├─ Application.java
│  │  ├─ controller/WebhookController.java
│  │  ├─ security/WebhookSignatureVerifier.java
│  │  ├─ service/WhatsAppClient.java
│  │  ├─ config/HttpClientsConfig.java
│  │  └─ model/wpp/*.java
│  └─ main/resources/
│     ├─ application.yml
│     └─ logback-spring.xml (opcional)
└─ README.md
```

---

## 2) `pom.xml` mínimo
```xml
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.fluxbeam</groupId>
  <artifactId>fluxbeam</artifactId>
  <version>0.1.0</version>
  <name>FluxBeam</name>
  <properties>
    <java.version>21</java.version>
    <spring-boot.version>3.3.2</spring-boot.version>
  </properties>
  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-dependencies</artifactId>
        <version>${spring-boot.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-webflux</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>
  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

---

## 3) Configuração (`application.yml`) e variáveis
```yaml
server:
  port: 8080

fluxbeam:
  wpp:
    token: ${WPP_TOKEN}
    phoneNumberId: ${PHONE_NUMBER_ID}
    appSecret: ${APP_SECRET}
  verifyToken: ${VERIFY_TOKEN:changeme}
```
**.env esperado**:
```
WPP_TOKEN=                 # Bearer (permanent token / system user)
PHONE_NUMBER_ID=           # ID do número do WhatsApp
APP_SECRET=                # App Secret p/ assinatura do webhook
VERIFY_TOKEN=              # para validação GET /webhook
```

---

## 4) Clientes HTTP
```java
// HttpClientsConfig.java
package com.fluxbeam.app.config;
import org.springframework.context.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
@Configuration
public class HttpClientsConfig {
  @Bean RestClient restClient() { return RestClient.create(); }
  @Bean WebClient webClient() { return WebClient.builder().build(); }
}
```

---

## 5) Verificação da assinatura (X‑Hub‑Signature‑256)
```java
// WebhookSignatureVerifier.java
package com.fluxbeam.app.security;
import javax.crypto.Mac; import javax.crypto.spec.SecretKeySpec; import java.nio.charset.StandardCharsets; import java.util.Locale;
public class WebhookSignatureVerifier {
  public static boolean verify(byte[] rawBody, String headerValue, String appSecret) {
    if (headerValue == null || !headerValue.startsWith("sha256=")) return false;
    String expected = hmacSha256Hex(rawBody, appSecret);
    String given = headerValue.substring("sha256=".length());
    return slowEquals(expected, given);
  }
  private static String hmacSha256Hex(byte[] body, String secret) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] out = mac.doFinal(body);
      StringBuilder sb = new StringBuilder();
      for (byte b : out) sb.append(String.format(Locale.ROOT, "%02x", b));
      return sb.toString();
    } catch (Exception e) { throw new IllegalStateException(e); }
  }
  private static boolean slowEquals(String a, String b) {
    if (a == null || b == null || a.length() != b.length()) return false;
    int r = 0; for (int i=0;i<a.length();i++) r |= a.charAt(i) ^ b.charAt(i); return r==0;
  }
}
```

---

## 6) Controller `/webhook` (GET valida, POST eco)
```java
// WebhookController.java
package com.fluxbeam.app.controller;
import com.fasterxml.jackson.databind.*; import com.fluxbeam.app.security.WebhookSignatureVerifier; import com.fluxbeam.app.service.WhatsAppClient;
import org.springframework.beans.factory.annotation.Value; import org.springframework.http.ResponseEntity; import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/webhook")
public class WebhookController {
  private final ObjectMapper mapper = new ObjectMapper();
  private final WhatsAppClient wpp;
  public WebhookController(WhatsAppClient wpp) { this.wpp = wpp; }
  @Value("${fluxbeam.wpp.appSecret}") String appSecret; @Value("${fluxbeam.verifyToken}") String verifyToken;

  @GetMapping
  public ResponseEntity<String> verify(@RequestParam(name="hub.mode", required=false) String mode,
                                       @RequestParam(name="hub.verify_token", required=false) String token,
                                       @RequestParam(name="hub.challenge", required=false) String challenge) {
    if ("subscribe".equals(mode) && verifyToken.equals(token)) return ResponseEntity.ok(challenge);
    return ResponseEntity.status(403).build();
  }

  @PostMapping
  public ResponseEntity<Void> receive(@RequestHeader(name="X-Hub-Signature-256", required=false) String sig,
                                      @RequestBody byte[] body) throws Exception {
    if (!WebhookSignatureVerifier.verify(body, sig, appSecret)) return ResponseEntity.status(401).build();
    JsonNode root = mapper.readTree(body);
    JsonNode msg = root.path("entry").path(0).path("changes").path(0).path("value").path("messages").path(0);
    String from = msg.path("from").asText(null);
    String text = msg.path("text").path("body").asText(null);
    if (from != null) {
      String reply = (text != null) ? "Eco: " + text : "Olá do FluxBeam 👋";
      wpp.sendText(from, reply);
    }
    return ResponseEntity.ok().build();
  }
}
```

---

## 7) Cliente WhatsApp (`/messages`)
```java
// WhatsAppClient.java
package com.fluxbeam.app.service;
import org.springframework.beans.factory.annotation.Value; import org.springframework.http.MediaType; import org.springframework.stereotype.Service; import org.springframework.web.client.RestClient; import java.util.Map;
@Service
public class WhatsAppClient {
  private final RestClient rest; public WhatsAppClient(RestClient rest) { this.rest = rest; }
  @Value("${fluxbeam.wpp.token}") String token; @Value("${fluxbeam.wpp.phoneNumberId}") String phoneNumberId;

  public void sendText(String toE164, String body) {
    String url = "https://graph.facebook.com/v19.0/" + phoneNumberId + "/messages";
    Map<String, Object> payload = Map.of(
      "messaging_product", "whatsapp",
      "to", toE164,
      "type", "text",
      "text", Map.of("body", body)
    );
    rest.post().uri(url)
      .contentType(MediaType.APPLICATION_JSON)
      .header("Authorization", "Bearer " + token)
      .body(payload).retrieve().toBodilessEntity();
  }

  // (P1) Enviar lista interativa
  public void sendList(String toE164, String bodyText, String button, Map<String, Object> section) {
    String url = "https://graph.facebook.com/v19.0/" + phoneNumberId + "/messages";
    Map<String, Object> payload = Map.of(
      "messaging_product", "whatsapp",
      "to", toE164,
      "type", "interactive",
      "interactive", Map.of(
        "type", "list",
        "body", Map.of("text", bodyText),
        "action", Map.of("button", button, "sections", new Object[]{ section })
      )
    );
    rest.post().uri(url).contentType(MediaType.APPLICATION_JSON)
      .header("Authorization", "Bearer " + token)
      .body(payload).retrieve().toBodilessEntity();
  }
}
```

---

## 8) Teste manual (cURL)
```bash
curl -X POST "https://graph.facebook.com/v19.0/$PHONE_NUMBER_ID/messages"   -H "Authorization: Bearer $WPP_TOKEN" -H "Content-Type: application/json"   -d '{
    "messaging_product": "whatsapp",
    "to": "<DESTINO_E164>",
    "type": "text",
    "text": { "body": "Olá do FluxBeam (Java) 👋" }
  }'
```

---

## 9) Roadmap P1/P2
- **Interativas**: helpers `sendList`/`sendButtons`; parse de cliques.
- **Templates**: criar, aprovar e enviar (categoria Utility/Marketing).
- **Flows**: versionar JSON em `resources/flows/` e enviar via Interactive Flow.
- **Engine**: SPI `Plugin` com `onMessage(event)`; roteamento por regras simples.
- **Resiliência**: retries, DLQ, idempotência por `messages[0].id`.
- **Observabilidade**: métricas e health-check.

---

## 10) Próximos passos
- [ ] `mvn spring-boot:run` + túnel (ngrok/cloudflared) e cadastro do webhook.
- [ ] Validar `GET /webhook` (VERIFY_TOKEN) e assinatura no `POST`.
- [ ] Enviar/receber **texto** real.
- [ ] Implementar `sendList` e 1 **template** (lembrete pt_BR).
- [ ] Definir SPI de **plugins** da engine.
