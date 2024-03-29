package xyz.xcye.article.service.impl;

import io.seata.spring.annotation.GlobalTransactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindException;
import xyz.xcye.admin.vo.UserVO;
import xyz.xcye.amqp.comstant.AmqpExchangeNameConstant;
import xyz.xcye.amqp.comstant.AmqpQueueNameConstant;
import xyz.xcye.api.mail.sendmail.entity.StorageSendMailInfo;
import xyz.xcye.api.mail.sendmail.enums.SendHtmlMailTypeNameEnum;
import xyz.xcye.api.mail.sendmail.service.SendMQMessageService;
import xyz.xcye.api.mail.sendmail.util.StorageMailUtils;
import xyz.xcye.article.api.service.ArticleUserFeignService;
import xyz.xcye.article.dao.LinkMapper;
import xyz.xcye.article.po.Category;
import xyz.xcye.article.po.Link;
import xyz.xcye.article.service.CategoryService;
import xyz.xcye.article.service.LinkService;
import xyz.xcye.article.vo.CategoryVO;
import xyz.xcye.article.vo.LinkVO;
import xyz.xcye.aurora.properties.AuroraProperties;
import xyz.xcye.aurora.util.UserUtils;
import xyz.xcye.core.entity.R;
import xyz.xcye.core.enums.ResponseStatusCodeEnum;
import xyz.xcye.core.exception.link.LinkException;
import xyz.xcye.core.exception.user.UserException;
import xyz.xcye.core.util.BeanUtils;
import xyz.xcye.core.util.JSONUtils;
import xyz.xcye.core.util.LogUtils;
import xyz.xcye.core.util.id.GenerateInfoUtils;
import xyz.xcye.core.util.lambda.AssertUtils;
import xyz.xcye.data.entity.Condition;
import xyz.xcye.data.entity.PageData;
import xyz.xcye.data.util.PageUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * @author qsyyke
 * @date Created in 2022/5/13 19:38
 */

@Service
public class LinkServiceImpl implements LinkService {

    @Autowired
    private AuroraProperties auroraProperties;
    @Autowired
    private LinkMapper linkMapper;
    @Autowired
    private CategoryService categoryService;
    @Autowired
    private ArticleUserFeignService userFeignService;
    @Autowired
    private SendMQMessageService sendMQMessageService;

    @Override
    public int deleteByPrimaryKey(Long uid, String replyMessage) throws BindException {
        Assert.notNull(uid, "uid不能为null");
        // 如果删除成功，通知对方
        LinkVO linkVO = selectByUid(uid);
        int deleteNum = linkMapper.deleteByPrimaryKey(uid);
        R r = userFeignService.queryUserByUid(linkVO.getUserUid());
        UserVO userVO = JSONUtils.parseObjFromResult(r, "data", UserVO.class);
        if (deleteNum == 1) {
            String mailMessage = "<p>您在" + userVO.getUsername() + "用户处申请的友链被移除！┭┮﹏┭┮</p><p>原因:" + replyMessage + "</p>";
            StorageSendMailInfo mailInfo = getMailInfo(linkVO.getEmail(), "友链被移除",
                    mailMessage, BeanUtils.copyProperties(linkVO, Link.class), SendHtmlMailTypeNameEnum.COMMON_NOTICE);
            sendMail(mailInfo, StorageMailUtils.generateCommonNotice(mailMessage));
        }
        return deleteNum;
    }

    @GlobalTransactional
    @Override
    public int insertSelective(Link record) throws BindException {
        Assert.notNull(record, "友情链接信息不能为null");
        Assert.notNull(record.getUserUid(), "用户uid不能为null");
        // 查看是否存在该用户
        R r = userFeignService.queryUserByUid(record.getUserUid());
        UserVO userVO = JSONUtils.parseObjFromResult(r, "data", UserVO.class);
        AssertUtils.stateThrow(userVO != null,
                () -> new UserException(ResponseStatusCodeEnum.PERMISSION_USER_NOT_EXIST));

        setCategory(record);
        record.setUid(GenerateInfoUtils.generateUid(auroraProperties.getSnowFlakeWorkerId(), auroraProperties.getSnowFlakeDatacenterId()));

        // 查看是否存在相似的链接
        List<LinkVO> result = selectByCondition(Condition.instant(record.getLinkUrl())).getResult();
        AssertUtils.stateThrow(result.isEmpty(), () -> new LinkException("已存在相同友情链接，不能重复提交"));

        // 审核应该通过用户进行控制
        record.setPublish(false);
        // 插入
        int insertNum = linkMapper.insertSelective(record);
        // 如果插入成功，则发送消息通知该用户
        if (insertNum == 1) {
            StorageSendMailInfo mailInfo = getMailInfo(null, "你有新的友情链接待审核",
                    "新友情链接信息: " + record, record, SendHtmlMailTypeNameEnum.FRIEND_LINK_NOTICE);
            try {
                List<Map<SendHtmlMailTypeNameEnum, Object>> replacedMailObject =
                        StorageMailUtils.generateReplacedMailObject(SendHtmlMailTypeNameEnum.FRIEND_LINK_NOTICE, record);
                sendMail(mailInfo, replacedMailObject);
            } catch (Exception e) {
                // 如果消息入库失败，会抛出异常，不处理
                LogUtils.logExceptionInfo(e);
            }
        }
        return insertNum;
    }

    @Override
    public PageData<LinkVO> selectByCondition(Condition<Long> condition) {
        Assert.notNull(condition, "查询条件不能为null");
        return PageUtils.pageList(condition, t -> linkMapper.selectByCondition(condition), LinkVO.class);
    }

    @Override
    public LinkVO selectByUid(Long uid) {
        Assert.notNull(uid, "uid不能为null");
        return BeanUtils.getSingleObjFromList(linkMapper.selectByCondition(Condition.instant(uid, true)), LinkVO.class);
    }

    @GlobalTransactional
    @Override
    public int updateByPrimaryKeySelective(Link record) {
        Assert.notNull(record, "友情链接信息不能为null");
        setCategory(record);
        Optional.ofNullable(UserUtils.getCurrentUserUid()).ifPresent(record::setUserUid);
        return linkMapper.updateByPrimaryKeySelective(record);
    }

    @Override
    public int updateLinkPublishStatus(long uid, boolean publish, String msg) throws BindException {
        LinkVO linkVO = selectByUid(uid);
        AssertUtils.stateThrow(linkVO != null, () -> new LinkException("没有该条记录"));
        AssertUtils.stateThrow(linkVO.getPublish() != publish, () -> new LinkException("该条友情链接的发布状态并未改变"));
        Link link = Link.builder().uid(uid).publish(publish).build();
        // 修改
        int updateNum = updateByPrimaryKeySelective(link);
        // 如果修改成功，发送消息通知对方
        linkVO.setPublish(publish);
        R r = userFeignService.queryUserByUid(linkVO.getUserUid());
        UserVO userVO = JSONUtils.parseObjFromResult(r, "data", UserVO.class);
        if (updateNum == 1 && !publish) {
            // 没有审核通过，则把message发送给该站长
            String mailMessage = "<p>您在" + userVO.getUsername() + "用户处申请的友链未通过审核！┭┮﹏┭┮</p><p>原因:" + msg + "</p>";
            StorageSendMailInfo mailInfo = getMailInfo(linkVO.getEmail(), "友链未通过",
                    mailMessage, BeanUtils.copyProperties(linkVO, Link.class), SendHtmlMailTypeNameEnum.COMMON_NOTICE);
            sendMail(mailInfo, StorageMailUtils.generateCommonNotice(mailMessage));
            return updateNum;
        }

        // 通过审核，发送消息通知对方
        if (updateNum == 1 && publish) {
            String mailMessage = "<p>您在" + userVO.getUsername() + "用户处申请的友链已通过审核！O(∩_∩)O哈哈~</p><p>博主留言:" + msg + "</p>";
            StorageSendMailInfo mailInfo = getMailInfo(linkVO.getEmail(), "友链通过",
                    mailMessage, BeanUtils.copyProperties(linkVO, Link.class), SendHtmlMailTypeNameEnum.COMMON_NOTICE);
            sendMail(mailInfo, StorageMailUtils.generateCommonNotice(mailMessage));
        }
        return updateNum;
    }

    /**
     * 设置类别，如果不存在，则插入
     * @param link
     */
    private void setCategory(Link link) {
        String categoryName = link.getCategoryName();
        AssertUtils.stateThrow(StringUtils.hasLength(categoryName),
                () -> new LinkException(ResponseStatusCodeEnum.PARAM_NOT_COMPLETE.getMessage()));
        CategoryVO categoryVO = categoryService.selectByTitle(categoryName);
        if (categoryVO == null) {
            // 插入
            Category category = Category.builder().title(categoryName).build();
            int insertNum = categoryService.insertSelective(category);
            // 插入失败，则不设置类别
            if (insertNum != 1) {
                link.setCategoryName(null);
            }
        }

    }

    private StorageSendMailInfo getMailInfo(String receiverEmail, String subject, String simpleText,
                                            Link link, SendHtmlMailTypeNameEnum mailTypeNameEnum) {
        StorageSendMailInfo mailInfo = new StorageSendMailInfo();
        mailInfo.setReceiverEmail(receiverEmail);
        mailInfo.setSubject(subject);
        mailInfo.setSimpleText(simpleText);
        mailInfo.setUserUid(link.getUserUid());
        mailInfo.setSendType(mailTypeNameEnum);
        return mailInfo;
    }

    private void sendMail(StorageSendMailInfo mailInfo, List<Map<SendHtmlMailTypeNameEnum,Object>> replacedObjList) throws BindException {
        sendMQMessageService.sendCommonMail(mailInfo, AmqpExchangeNameConstant.AURORA_SEND_MAIL_EXCHANGE,
                "topic", AmqpQueueNameConstant.SEND_HTML_MAIL_ROUTING_KEY, replacedObjList);
    }
}
