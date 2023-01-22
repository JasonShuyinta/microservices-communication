package com.dotjson.fraud;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Customer {

    private Integer id;
    private String firstName;
    private String lastName;
    private String email;
}
