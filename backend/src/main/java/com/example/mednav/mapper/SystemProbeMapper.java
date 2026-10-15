package com.example.mednav.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SystemProbeMapper {

    @Select("SELECT probe_value FROM system_probe WHERE probe_key = 'schema_version'")
    String selectSchemaVersion();
}

