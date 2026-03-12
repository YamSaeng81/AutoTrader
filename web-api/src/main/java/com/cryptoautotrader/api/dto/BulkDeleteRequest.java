package com.cryptoautotrader.api.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class BulkDeleteRequest {

    private List<Long> ids;
}
