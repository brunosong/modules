package com.brunosong.system.springai.pdf;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class VisionTargetResponse {
    private List<VisionTarget> targets;
}
