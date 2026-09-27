package com.evops.ticket.mask.jackson;

import com.evops.ticket.mask.MaskType;
import com.evops.ticket.mask.service.MaskService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对「无注解的裸 JSON 结构」（Map/List/标量，来自幂等快照反序列化）递归做敏感键脱敏。
 * 非 Map/List 值原样返回；Map 中仅替换当前生效规则收录的敏感字符串键，其余结构不变。
 * 非检票单据的 Map（无敏感键）零影响。
 */
class MaskTreeTransformer {

    private static final String BIZ_TYPE = "CheckinAppointment";

    private final MaskService maskService;

    MaskTreeTransformer(MaskService maskService) {
        this.maskService = maskService;
    }

    Object transform(Object value) {
        if (value instanceof Map) {
            return transformMap((Map<?, ?>) value);
        }
        if (value instanceof List) {
            List<?> source = (List<?>) value;
            List<Object> list = new ArrayList<>(source.size());
            for (Object item : source) {
                list.add(transform(item));
            }
            return list;
        }
        return value;
    }

    private Map<Object, Object> transformMap(Map<?, ?> map) {
        Map<Object, Object> copy = new LinkedHashMap<>(map.size());
        Object appointmentNo = map.get("appointmentNo");
        String bizId = appointmentNo == null ? null : String.valueOf(appointmentNo);
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Object key = entry.getKey();
            Object v = entry.getValue();
            if (key != null && v instanceof String) {
                String field = String.valueOf(key);
                MaskType type = maskService.currentRule().fieldType(field);
                if (type != null) {
                    v = maskService.evaluate(field, type, BIZ_TYPE, bizId, (String) v);
                }
            } else {
                v = transform(v);
            }
            copy.put(key, v);
        }
        return copy;
    }
}
