package com.wechatjava.service.impl;

import ai.z.openapi.ZhipuAiClient;
import ai.z.openapi.service.model.ChatCompletionCreateParams;
import ai.z.openapi.service.model.ChatCompletionResponse;
import ai.z.openapi.service.model.ChatMessageRole;
import com.wechatjava.entity.config.AppConfig;
import com.wechatjava.entity.constants.Constants;
import com.wechatjava.entity.dto.MessageSendDto;
import com.wechatjava.entity.dto.SysSettingDto;
import com.wechatjava.entity.dto.TokenUserInfoDto;
import com.wechatjava.entity.enums.*;
import com.wechatjava.entity.po.ChatMessage;
import com.wechatjava.entity.po.ChatSession;
import com.wechatjava.entity.po.UserContact;
import com.wechatjava.entity.query.ChatMessageQuery;
import com.wechatjava.entity.query.ChatSessionQuery;
import com.wechatjava.entity.query.SimplePage;
import com.wechatjava.entity.query.UserContactQuery;
import com.wechatjava.entity.vo.PaginationResultVO;
import com.wechatjava.exception.BusinessException;
import com.wechatjava.mappers.ChatMessageMapper;
import com.wechatjava.mappers.ChatSessionMapper;
import com.wechatjava.mappers.UserContactMapper;
import com.wechatjava.redis.RedisComponet;
import com.wechatjava.service.ChatMessageService;
import com.wechatjava.utils.CopyTools;
import com.wechatjava.utils.DateUtil;
import com.wechatjava.utils.StringTools;
import com.wechatjava.websocket.MessageHandler;
import jodd.util.ArraysUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.Resource;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;



/**
 * 聊天消息表 业务接口实现
 */
@Service("chatMessageService")
public class ChatMessageServiceImpl implements ChatMessageService {

    private static final Logger logger = LoggerFactory.getLogger(ChatMessageServiceImpl.class);

    @Value("${zhipu.ai.apiKey}")
    private String zhiPuApiKey;
    // 建议注入一个自定义线程池，避免耗尽系统资源
    @Resource(name = "messageExecutor")
    private AsyncTaskExecutor messageExecutor;

    @Resource
    private ChatMessageMapper<ChatMessage, ChatMessageQuery> chatMessageMapper;

    @Resource
    private ChatSessionMapper<ChatSession, ChatSessionQuery> chatSessionMapper;

    @Resource
    private MessageHandler messageHandler;

    @Resource
    private AppConfig appConfig;

    @Resource
    private UserContactMapper<UserContact, UserContactQuery> userContactMapper;

    @Resource
    private RedisComponet redisComponet;

    /**
     * 根据条件查询列表
     */
    @Override
    public List<ChatMessage> findListByParam(ChatMessageQuery param) {
        return this.chatMessageMapper.selectList(param);
    }

    /**
     * 根据条件查询列表
     */
    @Override
    public Integer findCountByParam(ChatMessageQuery param) {
        return this.chatMessageMapper.selectCount(param);
    }

    /**
     * 分页查询方法
     */
    @Override
    public PaginationResultVO<ChatMessage> findListByPage(ChatMessageQuery param) {
        int count = this.findCountByParam(param);
        int pageSize = param.getPageSize() == null ? PageSize.SIZE15.getSize() : param.getPageSize();

        SimplePage page = new SimplePage(param.getPageNo(), count, pageSize);
        param.setSimplePage(page);
        List<ChatMessage> list = this.findListByParam(param);
        PaginationResultVO<ChatMessage> result = new PaginationResultVO(count, page.getPageSize(), page.getPageNo(), page.getPageTotal(), list);
        return result;
    }

    /**
     * 新增
     */
    @Override
    public Integer add(ChatMessage bean) {
        return this.chatMessageMapper.insert(bean);
    }

    /**
     * 批量新增
     */
    @Override
    public Integer addBatch(List<ChatMessage> listBean) {
        if (listBean == null || listBean.isEmpty()) {
            return 0;
        }
        return this.chatMessageMapper.insertBatch(listBean);
    }

    /**
     * 批量新增或者修改
     */
    @Override
    public Integer addOrUpdateBatch(List<ChatMessage> listBean) {
        if (listBean == null || listBean.isEmpty()) {
            return 0;
        }
        return this.chatMessageMapper.insertOrUpdateBatch(listBean);
    }

    /**
     * 多条件更新
     */
    @Override
    public Integer updateByParam(ChatMessage bean, ChatMessageQuery param) {
        StringTools.checkParam(param);
        return this.chatMessageMapper.updateByParam(bean, param);
    }

    /**
     * 多条件删除
     */
    @Override
    public Integer deleteByParam(ChatMessageQuery param) {
        StringTools.checkParam(param);
        return this.chatMessageMapper.deleteByParam(param);
    }

    /**
     * 根据MessageId获取对象
     */
    @Override
    public ChatMessage getChatMessageByMessageId(Long messageId) {
        return this.chatMessageMapper.selectByMessageId(messageId);
    }

    /**
     * 根据MessageId修改
     */
    @Override
    public Integer updateChatMessageByMessageId(ChatMessage bean, Long messageId) {
        return this.chatMessageMapper.updateByMessageId(bean, messageId);
    }

    /**
     * 根据MessageId删除
     */
    @Override
    public Integer deleteChatMessageByMessageId(Long messageId) {
        return this.chatMessageMapper.deleteByMessageId(messageId);
    }


    @Override
    @Transactional(rollbackFor = Exception.class) // 保证本地数据库操作的原子性
    public MessageSendDto saveMessage(ChatMessage chatMessage, TokenUserInfoDto tokenUserInfoDto) {

        // 1. 校验好友/群组关系 (建议抽离私有方法)
        checkContactRelation(tokenUserInfoDto.getUserId(), chatMessage.getContactId());
        //发送者
        String sendUserId = tokenUserInfoDto.getUserId();
        //接受者
        String contactId = chatMessage.getContactId();
        Long curTime = System.currentTimeMillis();

        UserContactTypeEnum contactTypeEnum = UserContactTypeEnum.getByPrefix(contactId);
        MessageTypeEnum messageTypeEnum = MessageTypeEnum.getByType(chatMessage.getMessageType());

        // 2. 内容预处理，数据清洗，再放回
        String messageContent = StringTools.resetMessageContent(chatMessage.getMessageContent());
        chatMessage.setMessageContent(messageContent);

        // 3. 计算 SessionId
        String sessionId;
        if (UserContactTypeEnum.USER == contactTypeEnum) {
            //拼接发送者和接受者的id，渲染到一个窗口
            //a id=1001 b id=1002 a->b|| b->a = 10011002拼接需要工具类不然拼接后的id序号不一样
            sessionId = StringTools.getChatSessionId4User(new String[]{sendUserId, contactId});
        } else {
            sessionId = StringTools.getChatSessionId4Group(contactId);
        }

        // 4. 更新会话表 (Last Message)
        ChatSession chatSession = new ChatSession();
        String lastMessage = messageContent;
        if (UserContactTypeEnum.GROUP == contactTypeEnum && !MessageTypeEnum.GROUP_CREATE.getType().equals(messageTypeEnum.getType())) {
            lastMessage = tokenUserInfoDto.getNickName() + "：" + messageContent;
        }
        chatSession.setLastMessage(lastMessage);
        chatSession.setLastReceiveTime(curTime);
        chatSessionMapper.updateBySessionId(chatSession, sessionId);

        // 5. 保存消息表
        Integer status = MessageTypeEnum.MEDIA_CHAT == messageTypeEnum ?
                MessageStatusEnum.SENDING.getStatus() : MessageStatusEnum.SENDED.getStatus();

        chatMessage.setSessionId(sessionId);
        chatMessage.setSendUserId(sendUserId);
        chatMessage.setSendUserNickName(tokenUserInfoDto.getNickName());
        chatMessage.setSendTime(curTime);
        chatMessage.setContactType(contactTypeEnum.getType());
        chatMessage.setStatus(status);
        chatMessageMapper.insert(chatMessage);

        // 6. 构造返回对象
        MessageSendDto messageSend = CopyTools.copy(chatMessage, MessageSendDto.class);

        // 7. 异步逻辑处理
        if (Constants.ROBOT_UID.equals(contactId)) {
            // 情况 A: 接收者是机器人 -> 异步触发 AI 思考
            handleAiReplyAsync(messageContent, sendUserId, tokenUserInfoDto);
        } else {
            // 情况 B: 接收者是普通用户/群 -> 实时推送消息
            messageHandler.sendMessage(messageSend);
        }

        // 立即返回，前端不需要等待 AI 回复即可显示用户刚才发的消息
        return messageSend;
    }

    /**
     * 异步处理 AI 回复逻辑
     */
    private void handleAiReplyAsync(String userContent, String receiverId, TokenUserInfoDto originalUser) {
        CompletableFuture.runAsync(() -> {
            try {
                // 1. 调用 AI 接口 (耗时操作，不阻塞主线程)
                Object aiReply = callAI(userContent);

                // 2. 准备机器人信息
                SysSettingDto sysSettingDto = redisComponet.getSysSetting();
                TokenUserInfoDto robot = new TokenUserInfoDto();
                robot.setUserId(sysSettingDto.getRobotUid());
                robot.setNickName(sysSettingDto.getRobotNickName());

                // 3. 构造回复消息
                ChatMessage robotMsg = new ChatMessage();
                robotMsg.setContactId(receiverId);
                robotMsg.setMessageContent((String) aiReply);
                robotMsg.setMessageType(MessageTypeEnum.CHAT.getType());

                // 4. 递归调用 saveMessage 保存并推送 AI 的回复
                // 注意：这里需要通过 Spring 上下文调用以保证事务和 AOP 生效
                // 或者直接在此处重复部分保存逻辑
                saveMessage(robotMsg, robot);

            } catch (Exception e) {
                e.printStackTrace();
            }
        }, messageExecutor);
    }

    private void checkContactRelation(String userId, String contactId) {
        if (!Constants.ROBOT_UID.equals(userId)) {
            //拿到当前用户所有的好友 UID 和群组 ID 列表
            List<String> contactList = redisComponet.getUserContactList(userId);
            if (!contactList.contains(contactId)) {
                UserContactTypeEnum type = UserContactTypeEnum.getByPrefix(contactId);
                throw new BusinessException(type == UserContactTypeEnum.USER ?
                        ResponseCodeEnum.CODE_902 : ResponseCodeEnum.CODE_903);
            }
        }
    }

    private Object callAI(String userMessage) {
        ZhipuAiClient client = ZhipuAiClient.builder().ofZHIPU()
                    .apiKey(zhiPuApiKey)
                    .build();

            // 创建聊天完成请求
            ChatCompletionCreateParams request = ChatCompletionCreateParams.builder()
                    .model("glm-5")
                    .messages(Arrays.asList(
                            ai.z.openapi.service.model.ChatMessage.builder()
                                    .role(ChatMessageRole.USER.value())
                                    .content(userMessage)
                                    .build()
                    ))
                    .build();

            // 发送请求
            ChatCompletionResponse response = client.chat().createChatCompletion(request);
            // 获取回复
            if (response.isSuccess()) {
                Object reply = response.getData().getChoices().get(0).getMessage();
                System.out.println("AI 回复: " + reply);
                return ((ai.z.openapi.service.model.ChatMessage) reply).getContent();
            } else {
                System.err.println("错误: " + response.getMsg());
                return "对不起"+response.getMsg();
            }
    }

    @Override
    public void saveMessageFile(String userId, Long messageId, MultipartFile file, MultipartFile cover) {
        ChatMessage message = chatMessageMapper.selectByMessageId(messageId);
        if (null == message) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        if (!message.getSendUserId().equals(userId)) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }

        SysSettingDto sysSettingDto = redisComponet.getSysSetting();
        String fileSuffix = StringTools.getFileSuffix(file.getOriginalFilename());
        if (!StringTools.isEmpty(fileSuffix) && ArraysUtil.contains(Constants.IMAGE_SUFFIX_LIST, fileSuffix.toLowerCase())
                && file.getSize() > Constants.FILE_SIZE_MB * sysSettingDto.getMaxImageSize()) {
            return;
        } else if (!StringTools.isEmpty(fileSuffix) && ArraysUtil.contains(Constants.VIDEO_SUFFIX_LIST, fileSuffix.toLowerCase())
                && file.getSize() > Constants.FILE_SIZE_MB * sysSettingDto.getMaxVideoSize()) {
            return;
        } else if (!StringTools.isEmpty(fileSuffix) &&
                !ArraysUtil.contains(Constants.VIDEO_SUFFIX_LIST, fileSuffix.toLowerCase()) &&
                !ArraysUtil.contains(Constants.IMAGE_SUFFIX_LIST, fileSuffix.toLowerCase()) &&
                file.getSize() > Constants.FILE_SIZE_MB * sysSettingDto.getMaxFileSize()) {
            return;
        }
        String fileName = file.getOriginalFilename();
        String fileExtName = StringTools.getFileSuffix(fileName);
        String fileRealName = messageId + fileExtName;
        String month = DateUtil.format(new Date(message.getSendTime()), DateTimePatternEnum.YYYYMM.getPattern());
        File folder = new File(appConfig.getProjectFolder() + Constants.FILE_FOLDER_FILE + month);
        if (!folder.exists()) {
            folder.mkdirs();
        }

        File uploadFile = new File(folder.getPath() + "/" + fileRealName);
        try {
            file.transferTo(uploadFile);
            if (cover != null) {
                cover.transferTo(new File(uploadFile.getPath() + Constants.COVER_IMAGE_SUFFIX));
            }
        } catch (Exception e) {
            logger.error("上传文件失败", e);
            throw new BusinessException("文件上传失败");
        }
        ChatMessage updateInfo = new ChatMessage();
        updateInfo.setStatus(MessageStatusEnum.SENDED.getStatus());
        ChatMessageQuery messageQuery = new ChatMessageQuery();
        messageQuery.setMessageId(messageId);
        chatMessageMapper.updateByParam(updateInfo, messageQuery);

        MessageSendDto messageSend = new MessageSendDto();
        messageSend.setStatus(MessageStatusEnum.SENDED.getStatus());
        messageSend.setMessageId(message.getMessageId());
        messageSend.setMessageType(MessageTypeEnum.FILE_UPLOAD.getType());
        messageSend.setContactId(message.getContactId());
        messageHandler.sendMessage(messageSend);
    }

    @Override
    public File downloadFile(TokenUserInfoDto userInfoDto, Long messageId, Boolean cover) {
        ChatMessage message = chatMessageMapper.selectByMessageId(messageId);
        String contactId = message.getContactId();
        UserContactTypeEnum contactTypeEnum = UserContactTypeEnum.getByPrefix(contactId);
        if (UserContactTypeEnum.USER.getType().equals(contactTypeEnum) && !userInfoDto.getUserId().equals(message.getContactId())) {
            throw new BusinessException(ResponseCodeEnum.CODE_600);
        }
        if (UserContactTypeEnum.GROUP.getType().equals(contactTypeEnum)) {
            UserContactQuery userContactQuery = new UserContactQuery();
            userContactQuery.setUserId(userInfoDto.getUserId());
            userContactQuery.setContactType(UserContactTypeEnum.GROUP.getType());
            userContactQuery.setContactId(contactId);
            userContactQuery.setStatus(UserContactStatusEnum.FRIEND.getStatus());
            Integer contactCount = userContactMapper.selectCount(userContactQuery);
            if (contactCount == 0) {
                throw new BusinessException(ResponseCodeEnum.CODE_600);
            }
        }
        String month = DateUtil.format(new Date(message.getSendTime()), DateTimePatternEnum.YYYYMM.getPattern());
        File folder = new File(appConfig.getProjectFolder() + Constants.FILE_FOLDER_FILE + month);
        if (!folder.exists()) {
            folder.mkdirs();
        }
        String fileName = message.getFileName();
        String fileExtName = StringTools.getFileSuffix(fileName);
        String fileRealName = messageId + fileExtName;

        if (cover != null && cover) {
            fileRealName = fileRealName + Constants.COVER_IMAGE_SUFFIX;
        }
        File file = new File(folder.getPath() + "/" + fileRealName);
        if (!file.exists()) {
            logger.info("文件不存在");
            throw new BusinessException(ResponseCodeEnum.CODE_602);
        }
        return file;
    }
}