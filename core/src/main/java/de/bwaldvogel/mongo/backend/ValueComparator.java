package de.bwaldvogel.mongo.backend;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import de.bwaldvogel.mongo.bson.BsonRegularExpression;
import de.bwaldvogel.mongo.bson.Document;
import de.bwaldvogel.mongo.bson.ObjectId;

public class ValueComparator implements Comparator<Object> {

    private static final List<Class<?>> SORT_PRIORITY = new ArrayList<>();

    static {
        /*
         * http://docs.mongodb.org/manual/faq/developers/#what-is-the-compare-order-for-bson-types
         *
         * Null Numbers (ints, longs, doubles) Symbol, String Object Array
         * BinData ObjectID Boolean Date, Timestamp Regular Expression
         */

        SORT_PRIORITY.add(Number.class);
        SORT_PRIORITY.add(String.class);
        SORT_PRIORITY.add(Document.class);
        SORT_PRIORITY.add(byte[].class);
        SORT_PRIORITY.add(ObjectId.class);
        SORT_PRIORITY.add(Boolean.class);
        SORT_PRIORITY.add(Date.class);
        SORT_PRIORITY.add(BsonRegularExpression.class);
    }

    private static int compareTypes(Object value1, Object value2) {
        if (Missing.isNullOrMissing(value1) && Missing.isNullOrMissing(value2)) {
            return 0;
        } else if (Missing.isNullOrMissing(value1)) {
            return -1;
        } else if (Missing.isNullOrMissing(value2)) {
            return 1;
        }

        int t1 = getTypeOrder(value1);
        int t2 = getTypeOrder(value2);
        return Integer.compare(t1, t2);
    }

    @Override
    public int compare(Object value1, Object value2) {
        return compareValues(value1, value2);
    }

    static int compareValues(Object value1, Object value2) {
        if (value1 == value2) {
            return 0;
        }

        if (Missing.isNullOrMissing(value1) && Missing.isNullOrMissing(value2)) {
            return 0;
        }

        int typeComparision = compareTypes(value1, value2);
        if (typeComparision != 0) {
            return typeComparision;
        }

        Class<?> clazz = value1.getClass();

        if (ObjectId.class.isAssignableFrom(clazz)) {
            return ((ObjectId) value1).compareTo((ObjectId) value2);
        }

        if (Number.class.isAssignableFrom(clazz)) {
            Number number1 = Utils.normalizeNumber((Number) value1);
            Number number2 = Utils.normalizeNumber((Number) value2);
            return Double.compare(number1.doubleValue(), number2.doubleValue());
        }

        if (String.class.isAssignableFrom(clazz)) {
            return value1.toString().compareTo(value2.toString());
        }

        if (Date.class.isAssignableFrom(clazz)) {
            Date date1 = (Date) value1;
            Date date2 = (Date) value2;
            return date1.compareTo(date2);
        }

        if (Boolean.class.isAssignableFrom(clazz)) {
            boolean b1 = ((Boolean) value1).booleanValue();
            boolean b2 = ((Boolean) value2).booleanValue();
            return (!b1 && b2) ? -1 : (b1 && !b2) ? +1 : 0;
        }

        // lexicographic byte comparison 0x00 < 0xFF
        if (clazz.isArray()) {
            Class<?> componentType = clazz.getComponentType();
            if (byte.class.isAssignableFrom(componentType)) {
                byte[] bytes1 = (byte[]) value1;
                byte[] bytes2 = (byte[]) value2;
                if (bytes1.length != bytes2.length) {
                    return Integer.compare(bytes1.length, bytes2.length);
                } else {
                    for (int i = 0; i < bytes1.length; i++) {
                        int compare = compareUnsigned(bytes1[i], bytes2[i]);
                        if (compare != 0) return compare;
                    }
                    return 0;
                }
            }
        }

        if (Document.class.isAssignableFrom(clazz)) {
            return compareDocuments((Document) value1, (Document) value2);
        }

        throw new UnsupportedOperationException("can't compare " + clazz);
    }

    private static int compareDocuments(Document document1, Document document2) {
        List<String> keys1 = new ArrayList<>(document1.keySet());
        List<String> keys2 = new ArrayList<>(document2.keySet());
        for (int i = 0; i < Math.max(keys1.size(), keys2.size()); i++) {
            String key1 = i >= keys1.size() ? null : keys1.get(i);
            String key2 = i >= keys2.size() ? null : keys2.get(i);

            Object value1 = document1.getOrMissing(key1);
            Object value2 = document2.getOrMissing(key2);

            int typeComparison = compareTypes(value1, value2);
            if (typeComparison != 0) {
                return typeComparison;
            }

            int keyComparison = compareValues(key1, key2);
            if (keyComparison != 0) {
                return keyComparison;
            }

            int valueComparison = compareValues(value1, value2);
            if (valueComparison != 0) {
                return valueComparison;
            }
        }

        return 0;
    }

    private static int compareUnsigned(byte b1, byte b2) {
        return Integer.compare(b1 + Integer.MIN_VALUE, b2 + Integer.MIN_VALUE);
    }

    private static int getTypeOrder(Object obj) {
        for (int idx = 0; idx < SORT_PRIORITY.size(); idx++) {
            if (SORT_PRIORITY.get(idx).isAssignableFrom(obj.getClass())) {
                return idx;
            }
        }
        throw new UnsupportedOperationException("can't sort " + obj.getClass());
    }
}
