import { LockOutlined, MailOutlined, UserOutlined } from "@ant-design/icons";
import { Button, Form, Input, Modal, Typography, message } from "antd";
import { useState } from "react";
import { signup } from "../utils";

const { Paragraph, Text, Title } = Typography;

const SignupForm = ({ buttonClassName, buttonLabel = "Register" }) => {
  const [displayModal, setDisplayModal] = useState(false);
  const [loading, setLoading] = useState(false);

  const handleCancel = () => {
    setDisplayModal(false);
  };

  const onFinish = (data) => {
    setLoading(true);

    signup(data)
      .then(() => {
        setDisplayModal(false);
        message.success("Account created. Please sign in.");
      })
      .catch((err) => {
        message.error(err.message);
      })
      .finally(() => {
        setLoading(false);
      });
  };

  return (
    <>
      <Button
        className={buttonClassName}
        shape="round"
        type="primary"
        onClick={() => setDisplayModal(true)}
      >
        {buttonLabel}
      </Button>
      <Modal
        className="fashion-modal"
        title={null}
        open={displayModal}
        onCancel={handleCancel}
        footer={null}
        destroyOnClose
      >
        <div className="signup-modal__hero">
          <Text className="signup-modal__eyebrow">Create account</Text>
          <Title level={3} className="signup-modal__title">
            Join the polished version of the ordering flow.
          </Title>
          <Paragraph className="signup-modal__text">
            Register a customer profile so the storefront can save your identity
            for cart and order actions.
          </Paragraph>
        </div>

        <Form name="register" onFinish={onFinish} layout="vertical" preserve={false}>
          <Form.Item
            label="Email"
            name="email"
            rules={[{ required: true, message: "Please input your email" }]}
          >
            <Input className="auth-input" prefix={<MailOutlined />} placeholder="you@example.com" />
          </Form.Item>

          <Form.Item
            label="Password"
            name="password"
            rules={[{ required: true, message: "Please input your password" }]}
          >
            <Input.Password
              className="auth-input"
              prefix={<LockOutlined />}
              placeholder="Password"
            />
          </Form.Item>

          <Form.Item
            label="First name"
            name="first_name"
            rules={[{ required: true, message: "Please input your first name" }]}
          >
            <Input className="auth-input" prefix={<UserOutlined />} placeholder="First name" />
          </Form.Item>

          <Form.Item
            label="Last name"
            name="last_name"
            rules={[{ required: true, message: "Please input your last name" }]}
          >
            <Input className="auth-input" prefix={<UserOutlined />} placeholder="Last name" />
          </Form.Item>

          <Form.Item style={{ marginBottom: 0 }}>
            <Button className="auth-submit" type="primary" htmlType="submit" loading={loading} block>
              Create account
            </Button>
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
};

export default SignupForm;
