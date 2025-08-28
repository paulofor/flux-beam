package com.fluxbeam.app.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxbeam.app.security.WebhookSignatureVerifier;
import com.fluxbeam.app.service.WhatsAppClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/webhook")
public class WebhookController {
  private final ObjectMapper mapper = new ObjectMapper();
  private final WhatsAppClient wpp;

  public WebhookController(WhatsAppClient wpp) {
    this.wpp = wpp;
  }

  @Value("${fluxbeam.wpp.appSecret}")
  String appSecret;
  @Value("${fluxbeam.verifyToken}")
  String verifyToken;

  @GetMapping
  public ResponseEntity<String> verify(
      @RequestParam(name = "hub.mode", required = false) String mode,
      @RequestParam(name = "hub.verify_token", required = false) String token,
      @RequestParam(name = "hub.challenge", required = false) String challenge) {
    if ("subscribe".equals(mode) && verifyToken.equals(token)) {
      return ResponseEntity.ok(challenge);
    }
    return ResponseEntity.status(403).build();
  }

  @PostMapping
  public ResponseEntity<Void> receive(
      @RequestHeader(name = "X-Hub-Signature-256", required = false) String sig,
      @RequestBody byte[] body) throws Exception {
    if (!WebhookSignatureVerifier.verify(body, sig, appSecret)) {
      return ResponseEntity.status(401).build();
    }
    JsonNode root = mapper.readTree(body);
    JsonNode msg = root.path("entry").path(0).path("changes").path(0)
        .path("value").path("messages").path(0);
    String from = msg.path("from").asText(null);
    String text = msg.path("text").path("body").asText(null);
    if (from != null) {
      String reply = (text != null) ? "Eco: " + text : "Olá do FluxBeam 👋";
      wpp.sendText(from, reply);
    }
    return ResponseEntity.ok().build();
  }
}
