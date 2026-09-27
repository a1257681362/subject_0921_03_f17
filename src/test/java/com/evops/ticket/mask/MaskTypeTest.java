package com.evops.ticket.mask;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 打码策略单测：样式正确、空值透传、对已打码值再次打码幂等
 * （幂等快照回放可能二次套规则，不能越打越花）。
 */
class MaskTypeTest {

    @Test
    void name_keepsFirstCharOnly() {
        assertEquals("周*", MaskType.NAME.mask("周洋"));
        assertEquals("欧**", MaskType.NAME.mask("欧阳修"));
        assertEquals("*", MaskType.NAME.mask("张"));
        assertNull(MaskType.NAME.mask(null));
    }

    @Test
    void name_maskingIsIdempotent() {
        String once = MaskType.NAME.mask("欧阳修");
        assertEquals(once, MaskType.NAME.mask(once));
    }

    @Test
    void contact_keepsFirst3Last4ForMobile() {
        assertEquals("138****8000", MaskType.CONTACT.mask("13812348000"));
    }

    @Test
    void contact_maskingIsIdempotent() {
        String once = MaskType.CONTACT.mask("13812348000");
        assertEquals(once, MaskType.CONTACT.mask(once));
    }

    @Test
    void contact_nonMobileFallsBackToGeneric() {
        assertEquals("0***1", MaskType.CONTACT.mask("05711"));
        assertEquals("a*z", MaskType.CONTACT.mask("abz"));
        assertNull(MaskType.CONTACT.mask(null));
    }

    @Test
    void generic_keepsOuterCharsAndIsIdempotent() {
        assertEquals("张*丰", MaskType.GENERIC.mask("张三丰"));
        assertEquals("**", MaskType.GENERIC.mask("ab"));
        String once = MaskType.GENERIC.mask("ABCDEFG");
        assertEquals(once, MaskType.GENERIC.mask(once));
        // 已打码值（含 *）二次处理原样返回，不越打越花
        assertEquals("张*丰", MaskType.GENERIC.mask("张*丰"));
    }
}
