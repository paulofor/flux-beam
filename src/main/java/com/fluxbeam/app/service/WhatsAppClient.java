package com.fluxbeam.app.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Service
public class WhatsAppClient {
  private final RestClient rest;

  public WhatsAppClient(RestClient rest) {
    this.rest = rest;
  }

  @Value("${fluxbeam.wpp.token}")
  String token;
  @Value("${fluxbeam.wpp.phoneNumberId}")
  String phoneNumberId;

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
        .body(payload)
        .retrieve().toBodilessEntity();
  }
}
