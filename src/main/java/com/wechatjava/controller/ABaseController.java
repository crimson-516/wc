package com.wechatjava.controller;

import com.wechatjava.entity.constants.Constants;
import com.wechatjava.entity.dto.TokenUserInfoDto;
import com.wechatjava.entity.enums.ResponseCodeEnum;
import com.wechatjava.entity.vo.ResponseVO;
import com.wechatjava.exception.BusinessException;
import com.wechatjava.redis.RedisUtils;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;


public class ABaseController {


    protected static final String COOKIE_KEY_TOKEN = "token";

    protected static final String STATUC_SUCCESS = "success";

    protected static final String STATUC_ERROR = "error";


    @Resource
    private RedisUtils redisUtils;

    //将验证码结果以响应格式整体返回
    protected <T> ResponseVO getSuccessResponseVO(T t) {
        ResponseVO<T> responseVO = new ResponseVO<>();
        responseVO.setStatus(STATUC_SUCCESS);
        responseVO.setCode(ResponseCodeEnum.CODE_200.getCode());
        responseVO.setInfo(ResponseCodeEnum.CODE_200.getMsg());
        responseVO.setData(t);
        return responseVO;
    }

    protected <T> ResponseVO getBusinessErrorResponseVO(BusinessException e, T t) {
        ResponseVO vo = new ResponseVO();
        vo.setStatus(STATUC_ERROR);
        if (e.getCode() == null) {
            vo.setCode(ResponseCodeEnum.CODE_600.getCode());
        } else {
            vo.setCode(e.getCode());
        }
        vo.setInfo(e.getMessage());
        vo.setData(t);
        return vo;
    }

    protected <T> ResponseVO getServerErrorResponseVO(T t) {
        ResponseVO vo = new ResponseVO();
        vo.setStatus(STATUC_ERROR);
        vo.setCode(ResponseCodeEnum.CODE_500.getCode());
        vo.setInfo(ResponseCodeEnum.CODE_500.getMsg());
        vo.setData(t);
        return vo;
    }

    //获取token
    protected TokenUserInfoDto getTokenUserInfo(HttpServletRequest request) {
        String token = request.getHeader("token");
        TokenUserInfoDto tokenUserInfoDto = (TokenUserInfoDto) redisUtils.get(Constants.REDIS_KEY_WS_TOKEN + token);
        return tokenUserInfoDto;
    }

    protected void resetTokenUserInfo(HttpServletRequest request, TokenUserInfoDto tokenUserInfoDto) {
        String token = request.getHeader("token");
        redisUtils.setex(Constants.REDIS_KEY_WS_TOKEN + token, tokenUserInfoDto, Constants.REDIS_KEY_EXPIRES_DAY * 2);
    }
}
