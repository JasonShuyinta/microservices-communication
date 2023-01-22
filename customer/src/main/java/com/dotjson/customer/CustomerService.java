package com.dotjson.customer;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

@Service
@AllArgsConstructor
@Slf4j
public class CustomerService {

    private final CustomerRepository customerRepository;
    //private final RestTemplate restTemplate;
    private RabbitTemplate rabbitTemplate;


    public void registerCustomer(CustomerRegistrationRequest request) {
        Customer customer = Customer.builder()
                .firstName(request.firstName())
                .lastName(request.lastName())
                .email(request.email())
                .build();
        customerRepository.saveAndFlush(customer);
        rabbitTemplate.convertAndSend(RabbitMQConfig.MESSAGE_EXCHANGE, RabbitMQConfig.ROUTING_KEY, customer);
        log.info("Message published");

        //         FraudCheckResponse response = restTemplate.getForObject(
//                "http://FRAUD/api/v1/fraud-check/{customerId}",
//                FraudCheckResponse.class,
//                customer.getId()
//                );
//         if(response.isFraudster()) {
//             throw new IllegalStateException("fraudster");
//         }

    }
}
