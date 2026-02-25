package com.kira.server.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DrillingDataVO {

        private Integer id;

        private LocalDateTime date;

        private Double rpm600;

        private Double rpm300;

        private Double rpm200;

        private Double rpm100;

        private Double rpm6;

        private Double rpm3;

        private Double initialStaticTorque;

        private Double endStaticTorque;

        private Double apparentViscosity;

        private Double plasticViscosity;

        private Double dynamicShear;

        private Double dynamicPlasticRatio;

        private Double temperature;

        private Double density;

        private Double solidPhase;

        private Double oilPhase;

        private Double waterPhase;

        private Double ca;

        private Double cl;

    }
