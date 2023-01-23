package com.dotjson.fraud;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@AllArgsConstructor
public class FraudCheckService {

    private final FraudCheckHistoryRepository fraudCheckHistoryRepository;

    //RabbitListener should not have a return value unless you want to return a response
    //to the producer of the message
    @RabbitListener(queues = RabbitMQConfig.MESSAGE_QUEUE)
    public void getCustomerFromQueue(Customer customer) {
        log.info("Customer is {}", customer.toString());
        fraudCheckHistoryRepository.save(
                FraudCheckHistory.builder()
                        .customerId(customer.getId())
                        .isFraudster(true)
                        .createdAt(LocalDateTime.now())
                        .build()
        );
    }

    @KafkaListener(topics = "dotjson", groupId = "groupId")
    public void getCustomerFromKafka(String customerEmail) {
        log.info("Received {}", customerEmail);
        fraudCheckHistoryRepository.save(
                FraudCheckHistory.builder()
                        .customerId(0)
                        .isFraudster(true)
                        .createdAt(LocalDateTime.now())
                        .build()
        );
    }

    public boolean isFraudulentCustomer(Integer customerId) {
        fraudCheckHistoryRepository.save(
                FraudCheckHistory.builder()
                        .customerId(customerId)
                        .isFraudster(false)
                        .createdAt(LocalDateTime.now())
                        .build()
        );
        return false;
    }
}
