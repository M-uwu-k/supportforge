package com.supportforge.tool;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;


public enum ToolValueType {


    STRING {

        @Override
        public boolean accepts(Object value) {


            return value instanceof String;
        }
    },


    INTEGER {
        @Override
        public boolean accepts(Object value) {


            return value instanceof Byte
                    || value instanceof Short
                    || value instanceof Integer
                    || value instanceof Long
                    || value instanceof BigInteger;

        }
    },


    NUMBER {
        @Override
        public boolean accepts(Object value) {
            return value instanceof Number;

        }
    },


    BOOLEAN {
        @Override
        public boolean accepts(Object value) {
            return value instanceof Boolean;
        }
    },


    ARRAY {
        @Override
        public boolean accepts(Object value) {

            return value instanceof List<?>;
        }
    },


    OBJECT {
        @Override
        public boolean accepts(Object value) {

            return value instanceof Map<?, ?>;
        }
    };


    public abstract boolean accepts(Object value);
}
