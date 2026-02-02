package com.wzw.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzw.knowledge.model.entity.OcrRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * OCR记录Mapper接口
 * <p>
 * 继承MyBatis-Plus的BaseMapper，提供基础的CRUD操作
 * </p>
 *
 * @author wzw
 * @version 1.0
 */
@Mapper
public interface OcrRecordMapper extends BaseMapper<OcrRecord> {

}
