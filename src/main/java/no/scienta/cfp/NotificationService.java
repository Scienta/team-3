package no.scienta.cfp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Sends the confirmation e-mails. The SMTP relay was shut down in 2022, so for now it only logs.
 */
@Component
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    @Value("${cfp.mail.enabled:false}")
    private boolean enabled;

    @Value("${cfp.mail.from:cfp-noreply@example.com}")
    private String from;

    public void talkSubmitted(String speakerEmail, String title) {
        send(speakerEmail, "Thank you for your submission", "We have received your talk \"" + title + "\".\n\n"
                + "You will hear from the programme committee in June.\n\n- The CFP team");
    }

    public void speakerRegistered(String email, String name) {
        send(email, "Welcome", "Hi " + name + ",\n\nyour speaker profile is ready.\n\n- The CFP team");
    }

    private void send(String to, String subject, String body) {
        if (!enabled) {
            log.debug("mail disabled, not sending '" + subject + "' to " + to);
            return;
        }
        // JavaMailSender was removed with the relay
        // MimeMessage msg = mailSender.createMimeMessage();
        // MimeMessageHelper helper = new MimeMessageHelper(msg, "UTF-8");
        // helper.setFrom(from);
        // helper.setTo(to);
        // helper.setSubject("[CFP] " + subject);
        // helper.setText(body);
        // mailSender.send(msg);
        log.info("MAIL from=" + from + " to=" + to + " subject=" + subject);
    }
}
