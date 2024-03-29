package xyz.xcye.comment.service.impl;

import io.seata.spring.annotation.GlobalTransactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindException;
import xyz.xcye.amqp.comstant.AmqpExchangeNameConstant;
import xyz.xcye.amqp.comstant.AmqpQueueNameConstant;
import xyz.xcye.api.mail.sendmail.entity.StorageSendMailInfo;
import xyz.xcye.api.mail.sendmail.enums.SendHtmlMailTypeNameEnum;
import xyz.xcye.api.mail.sendmail.service.SendMQMessageService;
import xyz.xcye.aurora.properties.AuroraProperties;
import xyz.xcye.aurora.util.UserUtils;
import xyz.xcye.comment.dao.CommentMapper;
import xyz.xcye.comment.dto.CommentDTO;
import xyz.xcye.comment.enums.CommentPageTypeEnum;
import xyz.xcye.comment.manager.amqp.send.SendCommentToExchange;
import xyz.xcye.comment.po.Comment;
import xyz.xcye.comment.service.CommentService;
import xyz.xcye.comment.vo.CommentVO;
import xyz.xcye.comment.vo.ShowCommentVO;
import xyz.xcye.core.enums.ResponseStatusCodeEnum;
import xyz.xcye.core.exception.comment.CommentException;
import xyz.xcye.core.util.BeanUtils;
import xyz.xcye.core.util.id.GenerateInfoUtils;
import xyz.xcye.core.util.lambda.AssertUtils;
import xyz.xcye.data.entity.Condition;
import xyz.xcye.data.entity.PageData;
import xyz.xcye.data.util.PageUtils;

import java.util.*;

/**
 * @author qsyyke
 */

@Slf4j
@Service
public class CommentServiceImpl implements CommentService {

    @Autowired
    private AuroraProperties auroraProperties;
    @Autowired
    private CommentMapper commentMapper;

    @Autowired
    private SendMQMessageService sendMQMessageService;
    @Autowired
    private SendCommentToExchange sendCommentToExchange;

    @GlobalTransactional(rollbackFor = Exception.class)
    @Override
    public int insertComment(Comment comment) throws Throwable {
        Assert.notNull(comment,"评论不能为null");
        AssertUtils.stateThrow(comment.getPageUid() != null, () -> new CommentException("文章uid不能为null"));
        setDefaultProperty(comment);
        setPageType(comment, false);

        // 判断该条评论是回复评论还是单独的一条评论
        // 如果此条评论的replyCommentUid在数据库中不存在，则标记为新建的单独评论
        Comment queriedRepliedCommentDO = getCommentByUid(comment.getReplyCommentUid());
        boolean isReplyCommentFlag = queriedRepliedCommentDO != null;

        // 向数据库中插入此条评论
        int insertCommentNum = commentMapper.insertSelective(comment);

        // 使用mq通知邮件服务，发送评论的邮件通知
        if (isReplyCommentFlag) {
            sendCommentEmail(true, comment, queriedRepliedCommentDO);
        }else {
            sendCommentEmail(false, comment, null);
        }

        //如果运行到这里，不能确保邮件发送成功，但还是修改一下邮件发送状态
        comment.setEmailNotice(true);
        updateComment(comment);

        // 发送此评论到交换机，说说或者是文章修改CommentUidS的值
        sendCommentToExchange.sendCommentToMQ(comment);

        // 如果插入成功，并且是回复某条评论，则修改此被回复的评论的nextCommentUidArray值
        if (isReplyCommentFlag && insertCommentNum == 1) {
            queriedRepliedCommentDO.setNextCommentUidArray(getNextCommentUidArrayStr(queriedRepliedCommentDO,comment.getUid()));
            updateComment(queriedRepliedCommentDO);
        }
        return insertCommentNum;
    }

    @Override
    public int resendEmailNotice(long uid) throws BindException {
        AssertUtils.stateThrow(uid != 0, () -> new CommentException("无效的评论uid"));
        // 查询该uid所对应的评论是回复评论还是单独新建的评论
        CommentDTO commentDTO = queryByUid(uid);
        AssertUtils.stateThrow(commentDTO != null, () -> new CommentException(ResponseStatusCodeEnum.PARAM_IS_INVALID));
        Comment comment = BeanUtils.copyProperties(commentDTO, Comment.class);
        // 查询被回复的评论
        Comment repliedCommentInfo = null;
        if (commentDTO.getReplyCommentUid() != null) {
            repliedCommentInfo = BeanUtils.copyProperties(queryByUid(commentDTO.getReplyCommentUid()), Comment.class);
        }

        if (repliedCommentInfo != null) {
            // 回复评论
            sendCommentEmail(true, comment, repliedCommentInfo);
        }else {
            sendCommentEmail(false, comment, null);
        }

        // 修改该评论的邮件通知状态
        Comment buildComment = Comment.builder()
                .uid(uid).emailNotice(true)
                .build();
        return updateComment(buildComment);
    }

    @Override
    public int deleteComment(Long uid) {
        Assert.notNull(uid, "uid不能为null");
        return commentMapper.deleteByPrimaryKey(uid);
    }

    @Override
    public int updateComment(Comment comment) {
        Assert.notNull(comment, "评论信息不能为null");
        setPageType(comment, true);
        Optional.ofNullable(UserUtils.getCurrentUserUid()).ifPresent(comment::setUserUid);
        return commentMapper.updateByPrimaryKeySelective(comment);
    }

    @Override
    public int updateDeleteStatus(Comment comment) {
        Assert.notNull(comment, () -> "更新评论删除状态，不能为null");
        Optional.ofNullable(UserUtils.getCurrentUserUid()).ifPresent(comment::setUserUid);
        comment.setDelete(Optional.ofNullable(comment.getDelete()).orElse(false));
        return commentMapper.updateByPrimaryKeySelective(comment);
    }

    @Override
    public ShowCommentVO queryArticleComments(long[] arrayUid) {
        // 获取arrayUid中可用的uid
        List<Long> effectiveCommentUidList = getEffectiveCommentUid(arrayUid);

        // 获取arrayUid所涉及的所有评论
        List<CommentDTO> allCommentDTOList = new ArrayList<>();
        for (Long uid : effectiveCommentUidList) {
            Comment comment = getCommentByUid(uid);
            //没有发布或者已经删除的，也不展示
            if (comment == null || !comment.getShowComment()) {
                continue;
            }
            CommentDTO copyCommentDTO = BeanUtils.copyProperties(comment,CommentDTO.class);
            CommentDTO sonNode = getAllSingleParentNodeList(copyCommentDTO);
            allCommentDTOList.add(sonNode);
        }

        ShowCommentVO showCommentVO = new ShowCommentVO();
        showCommentVO.setCommentList(allCommentDTOList);
        showCommentVO.setParentNodeNum(allCommentDTOList.size());
        return showCommentVO;
    }

    @Override
    public PageData<CommentVO> queryAllComments(Condition<Long> condition) {
        Assert.notNull(condition, "查询条件不能为null");
        return PageUtils.pageList(condition, t -> commentMapper.selectByCondition(condition), CommentVO.class);
    }

    @Override
    public CommentDTO queryByUid(long uid) {
        return xyz.xcye.core.util.BeanUtils.getSingleObjFromList(
                commentMapper.selectByCondition(Condition.instant(uid, true)),CommentDTO.class);
    }

    private void setDefaultProperty(Comment comment) {
        // 初始化值
        comment.setUid(GenerateInfoUtils.generateUid(auroraProperties.getSnowFlakeWorkerId(),auroraProperties.getSnowFlakeDatacenterId()));
        comment.setShowComment(true);
        // 设置email的发送状态，这里目前只能保证消息是否成功投递到rabbitmq交换机中，不能保证email是否发送成功
        comment.setEmailNotice(false);
        comment.setDelete(false);
    }

    /**
     * 设置此评论是在哪种类型的页面上发布的，有文章，说说
     * @param comment
     */
    private void setPageType(Comment comment, boolean isUpdate) {
        if (isUpdate) {
            if (comment.getPageType() == null) {
                return;
            }
        }

        AssertUtils.stateThrow(comment.getPageType() != null,
                () -> new CommentException(ResponseStatusCodeEnum.PARAM_COMMENT_NOT_SUPPORT_PAGE_TYPE));

        CommentPageTypeEnum commentPageTypeEnum = null;
        try {
            commentPageTypeEnum = CommentPageTypeEnum.valueOf(comment.getPageType());
        } catch (IllegalArgumentException e) {
            // 不支持的文章类型
            throw new CommentException(ResponseStatusCodeEnum.PARAM_COMMENT_NOT_SUPPORT_PAGE_TYPE);
        }
        comment.setPageType(commentPageTypeEnum.toString());
    }

    /**
     * 判断此commentDO中的ReplyCommentUid是不是回复某条评论
     * <p>如果此ReplyCommentUid在数据库中不存在，也标识单独新建的一条评论</p>
     * @param comment
     * @return true是回复评论
     */
    private boolean isReplyComment(Comment comment) throws ReflectiveOperationException {
        //此评论就是单独新建的一条评论
        if (comment.getReplyCommentUid() == 0) {
            return false;
        }
        //判断是否存在
        return queryByUid(comment.getReplyCommentUid()) != null;
    }

    /**
     * 从数据库中查询uid所对应的评论数据，如果没有，返回null
     * @param commentUid
     * @return
     */
    private Comment getCommentByUid(Long commentUid) {
        //此评论就是单独新建的一条评论
        if (commentUid == null || commentUid == 0) {
            return null;
        }
        //判断是否存在
        Condition<Long> condition = new Condition<>();
        condition.setUid(commentUid);
        List<Comment> commentDOList = commentMapper.selectByCondition(condition);
        return xyz.xcye.core.util.BeanUtils.getSingleObjFromList(commentDOList,Comment.class);
    }

    /**
     * 判断此uid在数据库中是否存在
     * @param uid
     * @return
     */
    private boolean isExistsComment(Long uid) {
        return getCommentByUid(uid) != null;
    }

    /**
     * 获取arrayUid中有效的uid
     * @param arrayUid
     * @return
     */
    private List<Long> getEffectiveCommentUid(long[] arrayUid) {
        List<Long> listUid = new ArrayList<>();
        for (Long uid : arrayUid) {
            if (isExistsComment(uid)) {
                listUid.add(uid);
            }
        }
        return listUid;
    }

    /**
     *
     * @param commentDTO
     * @return
     */
    private CommentDTO getAllSingleParentNodeList(CommentDTO commentDTO) {
        List<Long> uidList = parseUidArray(commentDTO.getNextCommentUidArray());
        if (uidList.isEmpty()) {
            //commentDTO下没有任何的子评论，直接返回
            return commentDTO;
        }

        //存在子评论，循环获取
        for (Long uid : uidList) {
            // 当前的uid对应评论数据
            Comment comment = getCommentByUid(uid);
            if (comment == null) {
                continue;
            }

            //将此commentDO添加到singleParentCommentDTOList中
            CommentDTO copyCommentDTO = BeanUtils.copyProperties(comment, CommentDTO.class);
            if (commentDTO.getSonCommentList() == null) {
                commentDTO.setSonCommentList(new ArrayList<>());
            }
            commentDTO.getSonCommentList().add(copyCommentDTO);
            getAllSingleParentNodeList(copyCommentDTO);
        }
        return commentDTO;
    }

    /**
     * 根据传入的字符串返回按,分割后的List<Long>集合
     * @param nextCommentUidArray
     * @return
     */
    private List<Long> parseUidArray(String nextCommentUidArray) {
        if (!StringUtils.hasLength(nextCommentUidArray)) {
            return new ArrayList<>();
        }
        String[] arraySplit = nextCommentUidArray.split(",");
        List<Long> uidList = new ArrayList<>();
        for (String uidStr : arraySplit) {
            try {
                uidList.add(Long.valueOf(uidStr));
            } catch (NumberFormatException e) {
                e.printStackTrace();
            }
        }
        return uidList;
    }

    /**
     * 获取被回复的repliedCommonDO的NextCommentUid
     * @param repliedCommonDO
     * @param replyingUid
     * @return
     */
    private String getNextCommentUidArrayStr(Comment repliedCommonDO,Long replyingUid) {
        if ("".equals(repliedCommonDO.getNextCommentUidArray()) || repliedCommonDO.getNextCommentUidArray() == null) {
            return replyingUid + "";
        }else {
            return repliedCommonDO.getNextCommentUidArray() + "," + replyingUid;
        }
    }

    /**
     * 创建一个发送邮件的对象
     * @param comment
     * @return
     */
    private StorageSendMailInfo createReceiveCommentMailInfo(Comment comment) {
        StorageSendMailInfo mailInfo = new StorageSendMailInfo();
        mailInfo.setUserUid(comment.getUserUid());
        mailInfo.setSendType(SendHtmlMailTypeNameEnum.RECEIVE_COMMENT);
        mailInfo.setSubject(comment.getContent());
        return mailInfo;
    }

    /**
     *
     * @param comment
     * @return
     */
    private List<Map<SendHtmlMailTypeNameEnum,Object>> createReceiveList(Comment comment) {
        List<Map<SendHtmlMailTypeNameEnum,Object>> list = new ArrayList<>();
        Map<SendHtmlMailTypeNameEnum,Object> map = new HashMap<>();
        map.put(SendHtmlMailTypeNameEnum.RECEIVE_COMMENT,comment);
        list.add(map);
        return list;
    }

    private void sendCommentEmail(boolean isReplyCommentFlag, Comment comment, Comment queriedRepliedCommentDO) throws BindException {
        // 使用rabbitmq发送邮件通知对方 因为使用rabbitmq发送消息到交换机的过程是同步的，并且我们已经开启了seata的全局事务功能
        // 所以如果在发送mq消息的过程中，出现异常，能够回滚，从而能够保证插入评论后，能够保证评论和mq发送到交换机的消息同时成功或者失败
        // 因为发布确认是异步的，如果能进入到发布确认代码中，那么前面插入评论和消息一定都成功保存到数据库中了
        if (isReplyCommentFlag) {
            sendMQMessageService.sendReplyMail(comment, queriedRepliedCommentDO,
                    AmqpExchangeNameConstant.AURORA_SEND_MAIL_EXCHANGE,
                    "topic", AmqpQueueNameConstant.SEND_HTML_MAIL_ROUTING_KEY);
        }else {
            //不是回复评论 设置ReplyCommentUid标识
            comment.setReplyCommentUid(0L);
            //交换机发送消息 如果此commentDO.getUserUid()用户在au_email中不存在记录的话，会使用默认的模板进行邮件通知
            StorageSendMailInfo mailInfo = createReceiveCommentMailInfo(comment);
            sendMQMessageService.sendCommonMail(mailInfo, AmqpExchangeNameConstant.AURORA_SEND_MAIL_EXCHANGE,
                    "topic", AmqpQueueNameConstant.SEND_HTML_MAIL_ROUTING_KEY, createReceiveList(comment));
        }
    }
}
