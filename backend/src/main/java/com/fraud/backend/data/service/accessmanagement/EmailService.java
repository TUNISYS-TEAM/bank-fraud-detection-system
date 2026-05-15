package com.fraud.backend.data.service.accessmanagement;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.UnsupportedEncodingException;
import java.util.Map;

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
    private final EmailSettingsService emailSettingsService;  // Use dedicated service

    @Value("${spring.mail.username}")
    private String fromEmail;

    @Value("${spring.mail.from-name:Fraud Detection Platform}")
    private String fromName;

    /**
     * Send OTP email for email verification - ALWAYS ACTIVE (no setting check)
     */
    public void sendOtpEmail(String to, String otp, String name) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromEmail, fromName);
            helper.setTo(to);
            helper.setSubject("Fraud Detection Platform - Email Verification");
            helper.setReplyTo("support@fraud-detection.com");

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
     * Send verification success email - CHECK SETTING
     */
    public void sendVerificationSuccessEmail(String to, String name) {
        if (!emailSettingsService.isEmailNotificationsEnabled()) {
            log.info("Email notifications disabled, skipping verification success email to: {}", to);
            return;
        }
        
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromEmail, fromName);
            helper.setTo(to);
            helper.setSubject("Fraud Detection Platform - Email Verifié");
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

    /**
     * Send daily summary of critical alerts - CHECK BOTH SETTINGS
     */
    public void sendCriticalAlertsSummary(String to, String name, Map<String, Object> summaryData) {
        if (!emailSettingsService.isEmailNotificationsEnabled() || !emailSettingsService.isCriticalAlertsEnabled()) {
            log.info("Email notifications or critical alerts disabled, skipping summary to: {}", to);
            return;
        }
        
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromEmail, fromName);
            helper.setTo(to);
            helper.setSubject("Fraud Detection Platform - Critical Alerts Summary");
            helper.setReplyTo("support@fraud-detection.com");

            Context context = new Context();
            context.setVariable("name", name);
            context.setVariable("summary", summaryData);
            context.setVariable("year", java.time.Year.now().getValue());
            context.setVariable("supportEmail", "support@fraud-detection.com");

            String htmlContent = templateEngine.process("critical-alerts-summary", context);
            helper.setText(htmlContent, true);

            mailSender.send(message);
            log.info("Critical alerts summary email sent to: {}", to);
        } catch (MessagingException | UnsupportedEncodingException e) {
            log.error("Failed to send critical alerts summary to {}: {}", to, e.getMessage());
            throw new RuntimeException("Failed to send critical alerts summary email", e);
        }
    }

    /**
     * Send immediate alert for critical model drift - CHECK BOTH SETTINGS
     */
    public void sendCriticalModelDriftAlert(String to, String name, Map<String, Object> driftData) {
        if (!emailSettingsService.isEmailNotificationsEnabled() || !emailSettingsService.isModelDriftEnabled()) {
            log.info("Email notifications or model drift alerts disabled, skipping drift alert to: {}", to);
            return;
        }
        
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromEmail, fromName);
            helper.setTo(to);
            helper.setSubject("URGENT: Critical Model Drift Detected");
            helper.setReplyTo("support@fraud-detection.com");

            Context context = new Context();
            context.setVariable("name", name);
            context.setVariable("drift", driftData);
            context.setVariable("year", java.time.Year.now().getValue());
            context.setVariable("supportEmail", "support@fraud-detection.com");

            String htmlContent = templateEngine.process("critical-model-drift", context);
            helper.setText(htmlContent, true);

            mailSender.send(message);
            log.info("Critical model drift alert email sent to: {}", to);
        } catch (MessagingException | UnsupportedEncodingException e) {
            log.error("Failed to send critical model drift alert to {}: {}", to, e.getMessage());
            throw new RuntimeException("Failed to send critical model drift alert email", e);
        }
    }
}