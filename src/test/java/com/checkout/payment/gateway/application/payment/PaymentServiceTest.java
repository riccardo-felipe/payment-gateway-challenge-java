package com.checkout.payment.gateway.application.payment;

import static com.checkout.payment.gateway.testsupport.PaymentFixture.aPayment;
import static com.checkout.payment.gateway.testsupport.ProcessPaymentCommandFixture.aCommand;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.checkout.payment.gateway.application.payment.AuthorizationResult.Authorized;
import com.checkout.payment.gateway.application.payment.AuthorizationResult.Declined;
import com.checkout.payment.gateway.domain.payment.Payment;
import com.checkout.payment.gateway.domain.payment.PaymentRepository;
import com.checkout.payment.gateway.domain.payment.PaymentStatus;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentService")
class PaymentServiceTest {

  @Mock
  private AcquiringBankGateway acquiringBank;

  @Mock
  private PaymentRepository paymentRepository;

  @InjectMocks
  private PaymentService paymentService;

  @Captor
  private ArgumentCaptor<Payment> savedPayment;

  @Nested
  @DisplayName("processing a payment")
  class Processing {

    @Test
    @DisplayName("stores an approved payment with the code the bank issued")
    void storesAnAuthorizedPayment() {
      when(acquiringBank.authorize(any()))
          .thenReturn(new Authorized("auth-code-123"));

      paymentService.process(aCommand().build());

      verify(paymentRepository).save(savedPayment.capture());
      assertThat(savedPayment.getValue().status())
          .isEqualTo(PaymentStatus.AUTHORIZED);
      assertThat(savedPayment.getValue().authorizationCode())
          .isEqualTo("auth-code-123");
    }

    @Test
    @DisplayName("stores a declined payment, because a refusal is still a record to reconcile")
    void storesADeclinedPayment() {
      when(acquiringBank.authorize(any()))
          .thenReturn(new Declined());

      paymentService.process(aCommand().build());

      verify(paymentRepository).save(savedPayment.capture());
      assertThat(savedPayment.getValue().status())
          .isEqualTo(PaymentStatus.DECLINED);
      assertThat(savedPayment.getValue().authorizationCode())
          .isNull();
    }

    /**
     * The single most important assertion in this class. Persisting before the bank answers would
     * leave records of payments that never happened, and a merchant reconciling against them would
     * chase money that was never taken.
     */
    @Test
    @DisplayName("stores nothing when the bank cannot be reached")
    void doesNotPersistWhenTheBankFails() {
      when(acquiringBank.authorize(any()))
          .thenThrow(new AcquiringBankUnavailableException("bank is down"));

      assertThatThrownBy(() -> paymentService.process(aCommand().build()))
          .isInstanceOf(AcquiringBankUnavailableException.class);

      verify(paymentRepository, never()).save(any());
    }

    /**
     * The full PAN reaches the bank and stops there. Anything stored is subject to compliance rules
     * that the last four digits are not.
     */
    @Test
    @DisplayName("keeps the full card number out of storage")
    void storesOnlyTheLastFourDigits() {
      when(acquiringBank.authorize(any()))
          .thenReturn(new Authorized("auth-code-123"));

      paymentService.process(
          aCommand()
              .withCardNumber("2222405343248877")
              .build()
      );

      verify(paymentRepository).save(savedPayment.capture());
      assertThat(savedPayment.getValue().lastFourCardDigits())
          .isEqualTo("8877");
    }

    @Test
    @DisplayName("copies the remaining details from the command")
    void carriesTheCommandDetailsThrough() {
      when(acquiringBank.authorize(any()))
          .thenReturn(new Authorized("auth-code-123"));

      paymentService.process(
          aCommand()
              .withExpiry(4, 2030)
              .withCurrency("USD")
              .withAmount(1050)
              .build()
      );

      verify(paymentRepository).save(savedPayment.capture());
      assertThat(savedPayment.getValue().expiryMonth()).isEqualTo(4);
      assertThat(savedPayment.getValue().expiryYear()).isEqualTo(2030);
      assertThat(savedPayment.getValue().currency()).isEqualTo("USD");
      assertThat(savedPayment.getValue().amount()).isEqualTo(1050);
    }

    @Test
    @DisplayName("returns the same payment it stored")
    void returnsTheStoredPayment() {
      when(acquiringBank.authorize(any()))
          .thenReturn(new Authorized("auth-code-123"));

      Payment returned = paymentService.process(aCommand().build());

      verify(paymentRepository).save(savedPayment.capture());
      assertThat(returned).isEqualTo(savedPayment.getValue());
    }

    /**
     * Two identical requests are two payments. Reusing an id would let the second overwrite the
     * first in the repository, and the merchant would lose a transaction.
     */
    @Test
    @DisplayName("gives every payment its own id")
    void assignsAFreshIdPerPayment() {
      when(acquiringBank.authorize(any()))
          .thenReturn(new Authorized("auth-code-123"));

      Payment first = paymentService.process(aCommand().build());
      Payment second = paymentService.process(aCommand().build());

      assertThat(first.id()).isNotEqualTo(second.id());
    }
  }

  @Nested
  @DisplayName("retrieving a payment")
  class Retrieving {

    @Test
    @DisplayName("returns the stored payment for a known id")
    void returnsAKnownPayment() {
      Payment stored = aPayment().build();
      when(paymentRepository.findById(stored.id()))
          .thenReturn(Optional.of(stored));

      assertThat(paymentService.findById(stored.id())).isEqualTo(stored);
    }

    @Test
    @DisplayName("throws for an unknown id so the HTTP layer can answer 404")
    void throwsForAnUnknownId() {
      UUID unknownId = UUID.randomUUID();
      when(paymentRepository.findById(unknownId))
          .thenReturn(Optional.empty());

      assertThatThrownBy(() -> paymentService.findById(unknownId))
          .isInstanceOf(PaymentNotFoundException.class)
          .hasMessageContaining(unknownId.toString());
    }
  }
}