package com.dotjson.customer;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("api/v1/customers")
@AllArgsConstructor
public class CustomerController {

    private final CustomerService customerService;

    @PostMapping("/rest")
    public void registerCustomerRest(@RequestBody CustomerRegistrationRequest customerRegistrationRequest) {
        log.info("new customer registration with REST Template {}", customerRegistrationRequest);
        customerService.registerCustomerRestTemplate(customerRegistrationRequest);
    }

    @PostMapping("/rabbitMq")
    public void registerCustomer(@RequestBody CustomerRegistrationRequest customerRegistrationRequest) {
        log.info("new customer registration {}", customerRegistrationRequest);
        customerService.registerCustomer(customerRegistrationRequest);
    }

    @PostMapping("/kafka")
    public void registerCustomerKafka(@RequestBody CustomerRegistrationRequest customerRegistrationRequest) {
        log.info("customer registration with kafka {}", customerRegistrationRequest);
        customerService.registerCustomerKafka(customerRegistrationRequest);
    }

}
