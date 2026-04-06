package com.fraud.detection.payment;

import com.fraud.common.dto.TransactionEvent;
import com.fraud.detection.client.BankServiceClient;
import com.fraud.detection.model.PaymentRecord;
import com.fraud.detection.model.PaymentStatus;
import com.fraud.detection.producer.NotificationProducer;
import com.fraud.detection.service.RazorpayRefundService;
import com.fraud.detection.service.RedisService;
import com.fraud.common.dto.NotificationEvent;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentProcessorService {

    private final BankServiceClient bankServiceClient;
    private final RazorpayService razorpayService;
    private final RazorpayRefundService razorpayRefundService;
    private final RedisService redisService;
    private final MeterRegistry meterRegistry;
    private final NotificationProducer notificationProducer;

    private static final Map<PaymentStatus, Set<PaymentStatus>> VALID_TRANSITIONS = Map.of(
            PaymentStatus.PENDING,
            EnumSet.of(PaymentStatus.SUCCESS, PaymentStatus.FAILED,
                    PaymentStatus.COMPENSATING, PaymentStatus.RAZORPAY_ORDER_CREATED,
                    PaymentStatus.SKIPPED),
            PaymentStatus.COMPENSATING,
            EnumSet.of(PaymentStatus.COMPENSATED, PaymentStatus.COMPENSATION_FAILED)
    );

    // ================= ENTRY =================

    public void processPayment(TransactionEvent event) {

        long startMs = System.currentTimeMillis();
        String txId = event.getTransactionId();
        String traceId = MDC.get("traceId");
        if (traceId == null || traceId.isBlank()) traceId = "NO_TRACE-" + txId;


        if (!redisService.fraudResultExists(txId)) {
            log.error("[PAYMENT] Fraud result missing → abort {}", txId);
            return;
        }


        if (event.getPayeeUpiId() == null || event.getPayeeUpiId().isBlank()) {
            transition(null, PaymentStatus.SKIPPED, txId);
            saveRecord(buildRecord(event, null, null)
                    .paymentStatus(PaymentStatus.SKIPPED)
                    .failureReason("Non-UPI")
                    .completedAt(LocalDateTime.now())
                    .elapsedMs(elapsed(startMs))
                    .build());
            return;
        }

        //  Idempotency
        if (redisService.paymentRecordExists(txId)) {
            PaymentRecord existing = redisService.getPaymentRecord(txId);
            if (existing != null &&
                    EnumSet.of(PaymentStatus.SUCCESS, PaymentStatus.FAILED,
                                    PaymentStatus.COMPENSATED, PaymentStatus.COMPENSATION_FAILED,
                                    PaymentStatus.RAZORPAY_ORDER_CREATED, PaymentStatus.SKIPPED)
                            .contains(existing.getPaymentStatus())) {

                log.warn("Idempotent skip {}", txId);
                return;
            }
        }

        String paymentMode = event.getPaymentMode() != null ?
                event.getPaymentMode().toUpperCase() : "BANK";

        redisService.appendTimeline(txId, "PAYMENT_STARTED");

        transition(null, PaymentStatus.PENDING, txId);

        saveRecord(buildRecord(event, paymentMode, null)
                .paymentStatus(PaymentStatus.PENDING)
                .initiatedAt(LocalDateTime.now())
                .build());

        if ("RAZORPAY".equals(paymentMode)) {
            processRazorpay(event, startMs, traceId);
        } else {
            processBank(event, startMs, traceId);
        }
    }

    // ================= BANK =================

    private void processBank(TransactionEvent event, long startMs, String traceId) {

        String txId = event.getTransactionId();
        String payer = event.getPayerUserId();

        String payee = bankServiceClient.resolveUserIdByUpiId(event.getPayeeUpiId());

        //  Payee not found
        if (payee == null) {
            fail(event, "Payee not found", null, startMs);
            return;
        }

        //  Debit fail
        if (!bankServiceClient.debit(payer, event.getAmount(), txId)) {
            fail(event, "Debit failed", payee, startMs);
            return;
        }

        redisService.appendTimeline(txId, "DEBIT_DONE");

        //  Credit fail → Compensation
        if (!bankServiceClient.credit(payee, event.getAmount(), txId)) {

            transition(PaymentStatus.PENDING, PaymentStatus.COMPENSATING, txId);

            saveRecord(buildRecord(event,event.getPaymentMode(), payee)
                    .paymentStatus(PaymentStatus.COMPENSATING)
                    .failureReason("Credit failed")
                    .completedAt(LocalDateTime.now())
                    .build());

            boolean refundOk;
            //  HANDLE BOTH BANK + RAZORPAY
            if ("RAZORPAY".equalsIgnoreCase(event.getPaymentMode())) {

                String paymentId = redisService.getPaymentId(txId);

                if (paymentId == null) {
                    log.error("[REFUND]  paymentId missing for txId={}", txId);
                    refundOk = false;
                } else {
                    try {
                        razorpayRefundService.refund(paymentId, event.getAmount().intValue());
                        refundOk = true;
                    } catch (Exception e) {
                        log.error("[REFUND] Razorpay refund failed: {}", e.getMessage());
                        refundOk = false;
                    }
                }

            } else {
                // BANK REFUND
                refundOk = bankServiceClient.refund(payer, event.getAmount(), txId);
            }

            if (refundOk) {

                transition(PaymentStatus.COMPENSATING, PaymentStatus.COMPENSATED, txId);

                saveRecord(buildRecord(event, event.getPaymentMode(), payee)
                        .paymentStatus(PaymentStatus.COMPENSATED)
                        .compensationReason("Refund success")
                        .completedAt(LocalDateTime.now())
                        .elapsedMs(elapsed(startMs))
                        .build());

                notify(event, "COMPENSATED",
                        " Payment failed but refunded ₹" + event.getAmount(),
                        payee);

            } else {

                transition(PaymentStatus.COMPENSATING, PaymentStatus.COMPENSATION_FAILED, txId);

                saveRecord(buildRecord(event,event.getPaymentMode(), payee)
                        .paymentStatus(PaymentStatus.COMPENSATION_FAILED)
                        .compensationReason("Refund failed")
                        .completedAt(LocalDateTime.now())
                        .elapsedMs(elapsed(startMs))
                        .build());

                notify(event, "FAILED",
                        " Refund failed — contact support",
                        payee);
            }

            meterRegistry.counter("payment.failed").increment();
            return;
        }


        redisService.appendTimeline(txId, "CREDIT_DONE");

        transition(PaymentStatus.PENDING, PaymentStatus.SUCCESS, txId);

        saveRecord(buildRecord(event,event.getPaymentMode(), payee)
                .paymentStatus(PaymentStatus.SUCCESS)
                .completedAt(LocalDateTime.now())
                .elapsedMs(elapsed(startMs))
                .build());

        notify(event, "SUCCESS",
                " Payment successful ₹" + event.getAmount(),
                payee);

        meterRegistry.counter("payment.success").increment();
    }

    // ================= RAZORPAY =================

    private void processRazorpay(TransactionEvent event, long startMs, String traceId) {

        String txId = event.getTransactionId();

        String orderId = razorpayService.createOrder(event);

        if (orderId == null) {
            log.error("Razorpay failed → fallback BANK");
            processBank(event, startMs, traceId);
            return;
        }

        redisService.saveOrderMapping(orderId, txId);

        transition(PaymentStatus.PENDING, PaymentStatus.RAZORPAY_ORDER_CREATED, txId);

        saveRecord(buildRecord(event,event.getPaymentMode(), null)
                .paymentStatus(PaymentStatus.RAZORPAY_ORDER_CREATED)
                .razorpayOrderId(orderId)
                .completedAt(LocalDateTime.now())
                .elapsedMs(elapsed(startMs))
                .build());

        notify(event, "PENDING",
                " Complete payment via Razorpay",
                null);

        meterRegistry.counter("payment.success").increment();
    }

    // ================= COMMON =================

    private void fail(TransactionEvent event, String reason, String payee, long startMs) {

        String txId = event.getTransactionId();

        transition(PaymentStatus.PENDING, PaymentStatus.FAILED, txId);

        saveRecord(buildRecord(event,event.getPaymentMode(), payee)
                .paymentStatus(PaymentStatus.FAILED)
                .failureReason(reason)
                .completedAt(LocalDateTime.now())
                .elapsedMs(elapsed(startMs))
                .build());

        notify(event, "FAILED", " " + reason, payee);

        meterRegistry.counter("payment.failed").increment();
    }

    private void notify(TransactionEvent event, String status, String msg, String payee) {
        notificationProducer.send(buildNotification(event, status, msg, payee));
    }

    private NotificationEvent buildNotification(
            TransactionEvent event, String status, String message, String payee) {

        NotificationEvent n = new NotificationEvent();
        n.setTransactionId(event.getTransactionId());
        n.setStatus(status);
        n.setUserEmail(event.getPayerUserId());
        n.setPayeeEmail(payee);
        n.setPayeeUpiId(event.getPayeeUpiId());
        n.setAmount(event.getAmount());
        n.setMessage(message);
        return n;
    }

    private void transition(PaymentStatus from, PaymentStatus to, String txId) {
        if (from != null) {
            Set<PaymentStatus> allowed = VALID_TRANSITIONS.get(from);
            if (allowed == null || !allowed.contains(to)) {
                log.warn("Invalid transition {} → {}", from, to);
            }
        }
    }

    private PaymentRecord.PaymentRecordBuilder buildRecord(
            TransactionEvent event, String mode, String payee) {

        return PaymentRecord.builder()
                .transactionId(event.getTransactionId())
                .payerEmail(event.getPayerUserId())
                .payeeEmail(payee)
                .payeeUpiId(event.getPayeeUpiId())
                .amount(event.getAmount())
                .paymentMode(mode != null ? mode : "BANK");
    }

    private void saveRecord(PaymentRecord record) {
        try {
            redisService.savePaymentRecord(record);
        } catch (Exception e) {
            log.error("Save failed {}", e.getMessage());
        }
    }

    private long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }
}