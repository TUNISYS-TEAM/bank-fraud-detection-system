package com.fraud.backend.data.service.accessmanagement;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.UnsupportedEncodingException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;

    @Value("${spring.mail.username}")
    private String fromEmail;

    @Value("${spring.mail.from-name:Fraud Detection Platform}")
    private String fromName;

    /**
     * Send OTP email for email verification
     */
    public void sendOtpEmail(String to, String otp, String name) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            // Set from with display name using simple format
            helper.setFrom(fromEmail, fromName);
            helper.setTo(to);
            helper.setSubject("Fraud Detection Platform - Email Verification");
            
            // Add reply-to address
            helper.setReplyTo("support@fraud-detection.com");

            // Create HTML content
            Context context = new Context();
            context.setVariable("name", name);
            context.setVariable("otp", otp);
            context.setVariable("year", java.time.Year.now().getValue());
            context.setVariable("supportEmail", "support@fraud-detection.com");

            String htmlContent = templateEngine.process("otp-email", context);
            helper.setText(htmlContent, true);

            mailSender.send(message);
            log.info("OTP email sent to: {} from: {}", to, fromEmail);
        } catch (MessagingException | UnsupportedEncodingException e) {
            log.error("Failed to send OTP email to {}: {}", to, e.getMessage());
            throw new RuntimeException("Failed to send OTP email", e);
        }
    }

    /**
     * Send verification success email
     */
    public void sendVerificationSuccessEmail(String to, String name) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            // Set from with display name using simple format
            helper.setFrom(fromEmail, fromName);
            helper.setTo(to);
            helper.setSubject("Fraud Detection Platform - Email Verifié");
            
            // Add reply-to address
            helper.setReplyTo("support@fraud-detection.com");

            Context context = new Context();
            context.setVariable("name", name);
            context.setVariable("year", java.time.Year.now().getValue());
            context.setVariable("supportEmail", "support@fraud-detection.com");

            String htmlContent = templateEngine.process("verification-success", context);
            helper.setText(htmlContent, true);

            mailSender.send(message);
            log.info("Verification success email sent to: {}", to);
        } catch (MessagingException | UnsupportedEncodingException e) {
            log.error("Failed to send verification success email to {}: {}", to, e.getMessage());
        }
    }
}