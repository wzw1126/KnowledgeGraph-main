package com.wzw.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzw.knowledge.model.entity.Document;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文档Mapper接口
 * <p>
 * 继承MyBatis-Plus的BaseMapper，提供基础的CRUD操作
 * </p>
 *
 * @author wzw
 * @version 1.0
 */
@Mapper
public interface DocumentMapper extends BaseMapper<Document> {

}
