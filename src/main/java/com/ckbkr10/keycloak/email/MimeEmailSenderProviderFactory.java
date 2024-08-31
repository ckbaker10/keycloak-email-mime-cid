package com.ckbkr10.keycloak.email;

import org.keycloak.Config;
import org.keycloak.email.EmailSenderProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;


public class MimeEmailSenderProviderFactory implements EmailSenderProviderFactory {
    @Override
    public MimeEmailSenderProvider create(KeycloakSession session) {
        return new MimeEmailSenderProvider(session);
    }
  
    public void init(Config.Scope config) {
    }
    public void postInit(KeycloakSessionFactory factory) {
    }
    public void close() {
    }
    @Override
    public int order() {
        return 1;
    }
    public String getId() {
        return "email-mime";
    }
}

