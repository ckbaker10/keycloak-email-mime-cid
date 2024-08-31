package com.ckbkr10.keycloak.email;

import jakarta.mail.internet.MimeUtility;
import org.jboss.logging.Logger;
import org.keycloak.common.enums.HostnameVerificationPolicy;
import org.keycloak.email.DefaultEmailSenderProvider;
import org.keycloak.email.EmailException;
import org.keycloak.email.EmailSenderProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.UserModel;
import org.keycloak.services.ServicesLogger;
import org.keycloak.truststore.JSSETruststoreConfigurator;
import org.keycloak.vault.VaultStringSecret;

import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.Message;
import jakarta.mail.Transport;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.MimeMessage;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import java.util.Properties;
import static org.keycloak.utils.StringUtil.isNotBlank;

public class MimeEmailSenderProvider implements EmailSenderProvider {
    private static final Logger logger = Logger.getLogger(DefaultEmailSenderProvider.class);
    private static final String SUPPORTED_SSL_PROTOCOLS = getSupportedSslProtocols();

    private final KeycloakSession session;

    public MimeEmailSenderProvider(KeycloakSession session) {
        this.session = session;
    }
    /*   
    
     default void send(Map<String, String> config, UserModel user, String subject, String textBody, String htmlBody) throws EmailException {
        send(config, user.getEmail(), subject, textBody, htmlBody);
    }

    void send(Map<String, String> config, String address, String subject, String textBody, String htmlBody) throws EmailException;

     */

    @Override
    public void send(Map<String, String> config, UserModel user, String subject, String textBody, String htmlBody) throws EmailException {
        send(config, user.getEmail(), subject, textBody, htmlBody);
    }

    @Override
    public void send(Map<String, String> config, String address, String subject, String textBody, String htmlBody) throws EmailException {
        Transport transport = null;
        try {

            Properties props = new Properties();

            if (config.containsKey("host")) {
                props.setProperty("mail.smtp.host", config.get("host"));
            }

            boolean auth = "true".equals(config.get("auth"));
            boolean ssl = "true".equals(config.get("ssl"));
            boolean starttls = "true".equals(config.get("starttls"));

            if (config.containsKey("port") && config.get("port") != null) {
                props.setProperty("mail.smtp.port", config.get("port"));
            }

            if (auth) {
                props.setProperty("mail.smtp.auth", "true");
            }

            if (ssl) {
                props.setProperty("mail.smtp.ssl.enable", "true");
            }

            if (starttls) {
                props.setProperty("mail.smtp.starttls.enable", "true");
            }

            if (ssl || starttls || auth){
                props.put("mail.smtp.ssl.protocols", SUPPORTED_SSL_PROTOCOLS);

                setupTruststore(props);
            }

            props.setProperty("mail.smtp.timeout", "10000");
            props.setProperty("mail.smtp.connectiontimeout", "10000");

            String from = config.get("from");
            String fromDisplayName = config.get("fromDisplayName");
            String replyTo = config.get("replyTo");
            String replyToDisplayName = config.get("replyToDisplayName");
            String envelopeFrom = config.get("envelopeFrom");

            Session session = Session.getInstance(props);

            Multipart multipart = new MimeMultipart("alternative");

            if (textBody != null) {
                MimeBodyPart textPart = new MimeBodyPart();
                textPart.setText(textBody, "UTF-8");
                multipart.addBodyPart(textPart);
            }

            if (htmlBody != null) {
                MimeBodyPart htmlPart = new MimeBodyPart();
                htmlPart.setContent(htmlBody, "text/html; charset=UTF-8");
                htmlPart = this.addEmbeddedResources(htmlPart, htmlBody);
                multipart.addBodyPart(htmlPart);
            }

            Message msg = new MimeMessage(session);
            msg.setFrom(toInternetAddress(from, fromDisplayName));

            msg.setReplyTo(new Address[]{toInternetAddress(from, fromDisplayName)});

            if (isNotBlank(replyTo)) {
                msg.setReplyTo(new Address[]{toInternetAddress(replyTo, replyToDisplayName)});
            }

            if (isNotBlank(envelopeFrom)) {
                props.setProperty("mail.smtp.from", envelopeFrom);
            }

            msg.setHeader("To", address);
            msg.setSubject(MimeUtility.encodeText(subject, StandardCharsets.UTF_8.name(), null));
            msg.setContent(multipart);
            msg.saveChanges();
            msg.setSentDate(new Date());

            transport = session.getTransport("smtp");
            if (auth) {
                try (VaultStringSecret vaultStringSecret = this.session.vault().getStringSecret(config.get("password"))) {
                    transport.connect(config.get("user"), vaultStringSecret.get().orElse(config.get("password")));
                }
            } else {
                transport.connect();
            }
            transport.sendMessage(msg, new InternetAddress[]{new InternetAddress(address)});
        } catch (Exception e) {
            ServicesLogger.LOGGER.failedToSendEmail(e);
            throw new EmailException(e);
        } finally {
            if (transport != null) {
                try {
                    transport.close();
                } catch (MessagingException e) {
                    logger.warn("Failed to close transport", e);
                }
            }
        }
    }
    
    @Override
    public void close() {
    }

    private static java.util.regex.Pattern cidPattern = java.util.regex.Pattern.compile("[\"']cid:(.*?)[\"']");

    private MimeBodyPart addEmbeddedResources(MimeBodyPart htmlPart, String htmlBody) throws EmailException, MessagingException {

        // Search "cid:path" in body
        java.util.HashSet<String> paths = new java.util.HashSet<String>();
        java.util.regex.Matcher matcher = cidPattern.matcher(htmlBody);
        while (matcher.find()){
            paths.add(matcher.group(1));
            logger.debug("Found cid path: " + matcher.group(1));
        } 
        
        // If none, use htmlPart
        if (paths.size() == 0) return htmlPart;

        // Return new multipart with body and embedded files
        MimeMultipart content = new MimeMultipart("related");
        content.addBodyPart(htmlPart);
        
        String currentPath = "";
        try {
            org.keycloak.theme.Theme.Type themeType = org.keycloak.theme.Theme.Type.EMAIL;
            org.keycloak.theme.Theme theme = session.theme().getTheme(themeType);

            // Add each embedded file
            for (String path : paths) {
                currentPath = path;
                java.io.InputStream inputStream = theme.getResourceAsStream(path);
                if (inputStream != null) {
                    jakarta.activation.DataSource ds;
                    try {
                        String mimeType = java.nio.file.Files.probeContentType(new java.io.File(path).toPath());
                        ds = new jakarta.mail.util.ByteArrayDataSource(inputStream, mimeType);
                    } finally {
                        inputStream.close();
                    }
                    MimeBodyPart imagePart = new MimeBodyPart();
                    imagePart.setDataHandler(new jakarta.activation.DataHandler(ds));
                    imagePart.setContentID("<" + path + ">");
                    imagePart.setDisposition(MimeBodyPart.INLINE);
                    content.addBodyPart(imagePart);
                }
            }
        } catch (java.io.IOException e) {
            logger.debug("Couldn't add: " + currentPath);
            throw new EmailException("Error embedding resources", e);
        }
        
        MimeBodyPart contentPart = new MimeBodyPart();
        contentPart.setContent(content);
        return contentPart;
    }

    protected InternetAddress toInternetAddress(String email, String displayName) throws UnsupportedEncodingException, AddressException, EmailException {
        if (email == null || "".equals(email.trim())) {
            throw new EmailException("Please provide a valid address", null);
        }
        if (displayName == null || "".equals(displayName.trim())) {
            return new InternetAddress(email);
        }
        return new InternetAddress(email, displayName, "utf-8");
    }

    protected String retrieveEmailAddress(UserModel user) {
        return user.getEmail();
    }

    private void setupTruststore(Properties props) {
        JSSETruststoreConfigurator configurator = new JSSETruststoreConfigurator(session);

        SSLSocketFactory factory = configurator.getSSLSocketFactory();
        if (factory != null) {
            props.put("mail.smtp.ssl.socketFactory", factory);
            if (configurator.getProvider().getPolicy() == HostnameVerificationPolicy.ANY) {
                props.setProperty("mail.smtp.ssl.trust", "*");
                props.put("mail.smtp.ssl.checkserveridentity", Boolean.FALSE.toString()); // this should be the default but seems to be impl specific, so set it explicitly just to be sure
            }
            else {
                props.put("mail.smtp.ssl.checkserveridentity", Boolean.TRUE.toString());
            }
        }
    }

    private static String getSupportedSslProtocols() {
        try {
            String[] protocols = SSLContext.getDefault().getSupportedSSLParameters().getProtocols();
            if (protocols != null) {
                return String.join(" ", protocols);
            }
        } catch (Exception e) {
            logger.warn("Failed to get list of supported SSL protocols", e);
        }
        return null;
    }
  }
