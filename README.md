# Microservice communication

For microservices to communicate between them we have multiple options.
Here we explore 3 of these methods.

First you need at least 2 microservices that could run as a standalone application.
In this case we created a Customer microservice and a Fraud microservice.

The Customer microservice saves a new Customer object to a Postgres DB when hitting the "api/v1/customers" endpoint.

At that point we want to retrieve the newly saved Customer Id and use it to save a boolean value (*isFraudster*) to the
*fraud_check_history* table. The saving of this value in the table is done on the Fraud microservice.

To do this we need to communicate to the Fraud microservice the *customerId* after the Customer microservice has saved it 
in its table.

To boot everything up we use a docker-compose script: this yml file, creates a Postgres instance running at port
5432, and also adds to it the *pgadmin* image to have UI access to our DB running on port 5050. 

So when running 
```shell
docker compose up -d
```
open the web at *localhost:5050*, insert "password" as the master password, add a new Server, call it "amigoscode", and
as the host use "postgres".

To access tables go to Servers -> amigoscode -> Databases -> <tableName> -> Schemas -> public -> tables.

## RestTemplate
To communicate between microservices using RestTemplate you first of all need to instantiate a RestTemplate Bean in a config file.
```java

@Configuration
public class CustomerConfig {

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
```
Next up, at the Service level of your "producer" class, in this case, CustomerService, we inject the RestTemplate bean,
we then use the *.getForObject* method of RestTemplate to make a GET request to our Fraud microservice, passing in the 
host and port of the "consumer". 
```java
public record CustomerService(CustomerRepository customerRepository) {
    public void registerCustomer(CustomerRegistrationRequest request) {
        Customer customer = Customer.builder()
                .firstName(request.firstName())
                .lastName(request.lastName())
                .email(request.email())
                .build();
        customerRepository.saveAndFlush(customer);
        FraudCheckResponse response = restTemplate.getForObject(
                "http://localhost:8081/api/v1/fraud-check/{customerId}",
                FraudCheckResponse.class,
                customer.getId()
        );
        if (response.isFraudster()) {
            throw new IllegalStateException("fraudster");
        }

        customerRepository.save(customer);
    }
}
```
As you can see, you need to define the endpoint we want to hit, as well as the port: *http://localhost:8081/api/v1/fraud-check/{customerId}*.
This isn't very well suited for example when we will have multiple instances of the Fraud microservices, in which case we would need to implement some type of
Load Balancer to choose between which instance to hit.

To avoid having to hard-code host and port number you might as well use Spring Eureka as the Service Registry. Basically create a new microservice that holds the responsibility
of a service registry, annotate it with @EnableEurekaServer and add some configuration properties. Then add @EnableEurekaClient to each of your services (Customer and Fraud) and register
this services to the registry. At this point you don't need to hard code the host, but simply use the **spring.application.name** of your client. 
So in our case the endpoint would be something with the likes of "http://FRAUD-APPLICATION/api/v1/fraud-check/{customerId}".

Plus remember, that RestTemplate being an HTTP type of request, it is synchronous, so the Customer microservice is blocked until a response isn't
obtained from the Fraud microservice, which might impact the user experience, and keep stuck your entire microservice architecture.

At this point receiving the data from the consumer side is simple as creating a classic Controller. 
```java
public class FraudController {

    private final FraudCheckService fraudCheckService;

    @GetMapping(path = "{customerId}")
    public FraudCheckResponse isFraudster(@PathVariable Integer customerId) {
        boolean isFraudulentCustomer = fraudCheckService.isFraudulentCustomer(customerId);
        log.info("fraud check request for customer {}", customerId);
        return new FraudCheckResponse(isFraudulentCustomer);
    }
}
```

## RabbitMQ

For scalable applications, it is better to use a message broker such as RabbitMQ. It is asynchronous meaning that a producer writes its data
to a queue and there it stays until a consumer will read whenever its ready. In this way the Customer microservice doesn't remain stuck even
if the Fraud microservice is down and cannot receive the data from the producer.

To implement RabbitMQ, we added it to the docker-compose file using it as a container
```dockerfile
rabbitmq:
    image: rabbitmq:3.9.11-management-alpine
    container_name: rabbitmq
    ports:
      - "5672:5672"
      - "15672:15672"
```

We the import the dependencies into each of our microservices:

```xml
<dependecies>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-amqp</artifactId>
  </dependency>
  <dependency>
    <groupId>org.springframework.amqp</groupId>
    <artifactId>spring-rabbit-test</artifactId>
    <scope>test</scope>
  </dependency>
</dependecies>
```

RabbitMQ works as follows:

1. A Producer writes data into an *exchange*.
2. The exchange writes data into a *queue*.
3. The Consumer then, whenever it is able to, will read from the specific queue and will be able to use its data.

Producer --> Exchange --> Queue <-- Consumer.

To do so, we need a configuration file where we specify the Exchange, Queue and *RoutingKey* which binds the exchange with a particular queue, 
because it is possibile to have even multiple queue binded to a single exchange.

```java
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String MESSAGE_QUEUE = "message_queue";
    public static final String MESSAGE_EXCHANGE = "message_exchange";
    public static final String ROUTING_KEY = "message_routingKey";

    @Bean
    public Queue queue() {
        return new Queue(MESSAGE_QUEUE);
    }

    @Bean
    public TopicExchange exchange() {
        return new TopicExchange(MESSAGE_EXCHANGE);
    }

    @Bean
    public Binding binding(Queue queue, TopicExchange topicExchange) {
        return BindingBuilder.bind(queue).to(topicExchange).with(ROUTING_KEY);
    }

    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public AmqpTemplate template(ConnectionFactory connectionFactory) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(messageConverter());
        return rabbitTemplate;

    }
}
```

At this point we can inject the RabbitTemplate into our business logic layer as follows:

```java

@Service
@AllArgsConstructor
@Slf4j
public class CustomerService {
    
    private final CustomerRepository customerRepository;
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
    }
}
```

As you can see, the pass to the *convertAndSend* method the Exchange name and the Routing Key, so that the data (the *customer* object),
will be then forwarded to the Queue called "message_queue" we defined in *RabbitMQConfig*.

At this point at the Consumer level, we will have to implement the RabbitMQConfig as well and copy-paste the Customer object (might as well use a different module and keep the common dtos in here).

At the service layer we then annotate the method with @RabbitListener and give it the queue name.

```java
@Slf4j
@Service
@AllArgsConstructor
public class FraudCheckService {

    private final FraudCheckHistoryRepository fraudCheckHistoryRepository;
    
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
}
```

At this point this method will receive the Customer object that was sent from the Customer Service when we hit the "saveCustomer" endpoint.
#### IMPORTANT: 
The RabbitListener must be a void method, unless you want the Consumer to reply to the Producer. 

## Apache Kafka
To use Apache Kafka as the message broker, the bitname image was used, as it is the best maintained and
the best documented. 
To create the Kafka server you need to get the Zookeeper and Kafka broker images as follows:
```yaml
services:
  zookeeper:
    image: 'bitnami/zookeeper:latest'
    container_name: zookeeper
    ports:
      - '2181:2181'
    environment:
      - ALLOW_ANONYMOUS_LOGIN=yes
    networks:
      - postgres
  kafka:
    image: 'bitnami/kafka:latest'
    container_name: kafka
    ports:
      - '9092:9092'
    environment:
      - KAFKA_CFG_LISTENERS=PLAINTEXT://:9092
      - KAFKA_CFG_ADVERTISED_LISTENERS=PLAINTEXT://127.0.0.1:9092
      - KAFKA_CFG_ZOOKEEPER_CONNECT=zookeeper:2181
      - ALLOW_PLAINTEXT_LISTENER=yes
    networks:
      - postgres
```
It is important to have the same networks on which your microservices are running on. In our
case the network name is *postgres*.

#### Producer Configuration
The producer is the microservice that sends the message to the broker, so in our case
it is the Customer microservice.
There is some configuration needed to be done to use Kafka, like giving it the bootstrap servers as well as the
topic on which the Producer should write to:
```java
@Configuration
@RequiredArgsConstructor
public class KafkaProducerConfig {

    @Bean
    public Map<String, Object> producerConfig() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return props;
    }

    @Bean
    public ProducerFactory<String, String> producerFactory() {
        return new DefaultKafkaProducerFactory<>(producerConfig());
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    @Bean
    public NewTopic dotjsonTopic() {
        return TopicBuilder.name("dotjson").build();
    }
}
```

Next, in the service layer, or wherever you want to be able to send the message to the queue, you implement the
KafkaTemplate and use the *send* method, giving in the topic and the payload.
```java
@Service
@AllArgsConstructor
@Slf4j
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public void registerCustomerKafka(CustomerRegistrationRequest request) {
        Customer customer = Customer.builder()
                .firstName(request.firstName())
                .lastName(request.lastName())
                .email(request.email())
                .build();
        customerRepository.saveAndFlush(customer);
        kafkaTemplate.send("dotjson", customer.getEmail());
        log.info("Published to Kafka");
    }
}
```

If you want to be able to receive the messages from Kafka you need to setup some
configuration on the Consumer as well. 


Basically implement the following configuration in the Fraud microservice, where as before
we give to Kafka the bootstrap server and the topic -> *dotjson*.
```java
@Configuration
@RequiredArgsConstructor
public class KafkaConsumerConfig {

    @Bean
    public Map<String, Object> consumerConfig() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return props;
    }

    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        return new DefaultKafkaConsumerFactory<>(consumerConfig());
    }

    @Bean
    public KafkaListenerContainerFactory<ConcurrentMessageListenerContainer<String, String>> factory(ConsumerFactory<String, String> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        return factory;
    }

    @Bean
    public NewTopic dotjsonTopic() {
        return TopicBuilder.name("dotjson").build();
    }
}
```

Next, in the Consumer we annotate the method with @KafkaListener, give the topic and 
the groupId and as a parameter we pass the payload we want to receive from the Kafka queue.

```java
@Slf4j
@Service
@AllArgsConstructor
public class FraudCheckService {

    private final FraudCheckHistoryRepository fraudCheckHistoryRepository;

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
}
```

## Conclusion
We have here seen how to implement 3 types of microservice communication:
1. ***REST Template***: it uses HTTP protocol, so it is a synchronous way of communicating. Might be good for small applications, but it blocks your entire architecture if the microservice cannot receive the payload.
2. ***RabbitMQ***: It is super-easy to configure and implement and also easy to use. It uses the Message Queue Protocol so it is asynchronous, giving it a big advantage on the REST Template. 
3. ***Apache Kafka***: a bit difficult to implement and configure, it is asynchronous as well but it is able to persist the messagges in the queue forever, so it is a big advantage. Industry-standard so it might be useful to learn.

#### Author
Jason Shuyinta