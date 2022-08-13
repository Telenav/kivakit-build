package com.telenav.cactus.wordy;

import static com.telenav.cactus.wordy.WordLists.nearestPowerOfTwoLessThan;
import static java.lang.Long.numberOfTrailingZeros;
import java.util.function.Consumer;

/**
 * A list of words which can be looked up by index, which can express how many
 * bits can possibly be distinctly represented by its contents.
 *
 * @author Tim Boudreau
 */
public interface WordList {

    int size();

    String word(int index);

    default int bits() {
        return numberOfTrailingZeros(nearestPowerOfTwoLessThan(size()));
    }

    default int indexOf(String word) {
        int sz = size();
        for (int i = 0; i < sz; i++) {
            if (word.equals(word(i))) {
                return i;
            }
        }
        return -1;
    }

    default BitsConsumer toBitsConsumer(Consumer<String> c) {
        int bits = bits();
        return value -> {
            long masked = mask() & value;
            int val = (int) masked;
            c.accept(word(val));
            return bits;
        };
    }

    default long mask() {
        long result = 0;
        for (int i = 0; i <= bits(); i++) {
            result |= 1 << i;
        }
        return result;
    }

}
