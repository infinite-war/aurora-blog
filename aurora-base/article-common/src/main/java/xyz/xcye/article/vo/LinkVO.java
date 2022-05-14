package xyz.xcye.article.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.validator.constraints.Length;
import xyz.xcye.core.constant.FieldLengthConstant;
import xyz.xcye.core.valid.Delete;
import xyz.xcye.core.valid.Insert;
import xyz.xcye.core.valid.Update;
import xyz.xcye.core.valid.validator.ValidateString;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Null;
import java.io.Serializable;

@ApiModel(value="au_link")
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LinkVO implements Serializable {
    /**
     * 唯一uid
     */
    @ApiModelProperty(value="唯一uid")
    private Long uid;

    /**
     * 此条友情链接是哪个用户的
     */
    @ApiModelProperty(value="此条友情链接是哪个用户的")
    private Long userUid;

    /**
     * 此条友情链接属于哪个分类
     */
    @ApiModelProperty(value="此条友情链接属于哪个分类")
    private Long categoryUid;

    /**
     * logo地址
     */
    @ApiModelProperty(value="logo地址")
    private String linkLogo;

    /**
     * 链接地址
     */
    @ApiModelProperty(value="链接地址")
    private String linkUrl;

    /**
     * 对方的名称
     */
    @ApiModelProperty(value="对方的名称")
    private String linkTitle;

    /**
     * 描述信息
     */
    @ApiModelProperty(value="描述信息")
    private String linkDescription;

    /**
     * 对方站点的封面图
     */
    @ApiModelProperty(value="对方站点的封面图")
    private String linkCover;

    /**
     * 是否展示此条友情链接 1：展示 0：不展示
     */
    @ApiModelProperty(value="是否展示此条友情链接 1：展示 0：不展示")
    private Boolean publish;

    /**
    * 此友情链接对应的站长邮箱
    */
    @ApiModelProperty(value="此友情链接对应的站长邮箱")
    private String email;

    /**
    * 此友情链接对应的站长的qq号
    */
    @ApiModelProperty(value="此友情链接对应的站长的qq号")
    private Integer qqNumber;

    /**
    * 创建时间
    */
    @ApiModelProperty(value="创建时间")
    @Null
    private String createTime;

    /**
    * 最后更新时间
    */
    @ApiModelProperty(value="最后更新时间")
    private String updateTime;

    private static final long serialVersionUID = 1L;
}